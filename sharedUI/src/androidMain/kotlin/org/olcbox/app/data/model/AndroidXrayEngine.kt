package org.olcbox.app.data.model

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

class AndroidXrayEngine(
    private val context: Context
) : XrayEngine {
    companion object {
        private const val TAG = "XrayEngine"
        private const val START_TIMEOUT_MS = 30_000L
        private const val PORT_POLL_MS = 200L
        private const val STOP_TIMEOUT_MS = 5_000L
    }

    private val _state = MutableStateFlow(XrayEngineState())
    override val state: StateFlow<XrayEngineState> = _state.asStateFlow()

    private var xrayProcess: Process? = null
    private var monitorThread: Thread? = null
    private val errorOutput = mutableListOf<String>()
    override val isRunning: Boolean get() = xrayProcess?.isAlive == true

    private fun extractBinary(): String? {
        val libDir = context.applicationInfo.nativeLibraryDir
        val sourceFile = File(libDir, "libvless.so")
        if (!sourceFile.exists() || !sourceFile.canRead()) {
            Log.e(TAG, "Binary not found at ${sourceFile.absolutePath} (exists=${sourceFile.exists()})")
            return null
        }

        val destFile = File(context.filesDir, "vless-${UUID.randomUUID()}")
        if (destFile.exists()) {
            destFile.delete()
        }

        try {
            sourceFile.copyTo(destFile)
            try {
                Os.chmod(destFile.absolutePath, 0x1ED)
            } catch (e: ErrnoException) {
                Log.w(TAG, "Os.chmod failed, trying fallback", e)
                destFile.setExecutable(true, false)
                destFile.setWritable(true, false)
                destFile.setReadable(true, false)
            }

            var actualMode = -1
            try {
                actualMode = Os.stat(destFile.absolutePath).st_mode
            } catch (e: ErrnoException) {
                Log.w(TAG, "Failed to stat file", e)
            }
            val permStr = String.format("%o", actualMode and 0xFFF)
            Log.d(TAG, "Extracted ${destFile.length()} bytes to ${destFile.absolutePath}, perms=$permStr")
            return destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract binary", e)
            return null
        }
    }

private fun findLinker(): String? {
        val candidates = listOf(
            "/system/bin/linker64",
            "/system/bin/linker"
        )
        return candidates.firstOrNull { File(it).exists() }
    }

    override suspend fun startXray(config: XrayConfig): Result<Unit> = withContext(Dispatchers.IO) {
        if (isRunning) {
            return@withContext Result.failure(IllegalStateException("xray is already running"))
        }

        val binaryPath = extractBinary()
        if (binaryPath == null) {
            val err = "vless-client binary not found in native library dir"
            Log.e(TAG, err)
            _state.value = XrayEngineState(XrayEngine.STATE_ERROR, err)
            return@withContext Result.failure(RuntimeException(err))
        }

        val workingDir = File(context.filesDir, "vless-run-${UUID.randomUUID()}")
        workingDir.mkdirs()

        val cmd = buildList {
            add("-link")
            add(config.vlessLink)
            add("-listen")
            add("127.0.0.1:${config.listenPort}")
            if (config.socksUsername.isNotBlank()) {
                add("-proxy-user")
                add(config.socksUsername)
            }
            if (config.socksPassword.isNotBlank()) {
                add("-proxy-pass")
                add(config.socksPassword)
            }
            config.dnsServer?.let {
                add("-dns")
                add(it)
            }
            add("-debug")
        }

        val linker = findLinker()
        val fullCmd = if (linker != null) {
            listOf(linker, binaryPath) + cmd
        } else {
            listOf(binaryPath) + cmd
        }
        Log.i(TAG, "Starting vless-client: ${fullCmd.joinToString(" ")}")
        _state.value = XrayEngineState(XrayEngine.STATE_STARTING)
        errorOutput.clear()

        val process = try {
            val builder = ProcessBuilder(fullCmd)
                .directory(workingDir)
                .redirectErrorStream(false)
            builder.environment()["GODEBUG"] = "netdns=go"
            config.networkHandle?.let { handle ->
                builder.environment()["ANDROID_NETWORK_HANDLE"] = handle.toString()
                Log.d(TAG, "Set ANDROID_NETWORK_HANDLE=$handle for vless-client")
            }
            builder.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start vless-client process", e)
            _state.value = XrayEngineState(XrayEngine.STATE_ERROR, e.message)
            workingDir.deleteRecursively()
            File(binaryPath).delete()
            return@withContext Result.failure(e)
        }

        xrayProcess = process
        Log.i(TAG, "vless-client process started: $process")
        monitorThread = Thread({
            try {
                val errReader = java.io.BufferedReader(java.io.InputStreamReader(process.errorStream))
                errReader.lines().forEach { line ->
                    Log.d("vless-client", line)
                    errorOutput += line
                    config.onLogEntry?.invoke(line)
                }
            } catch (e: Exception) {
                Log.w(TAG, "vless-client error reader stopped", e)
            }
            try {
                val outReader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                outReader.lines().forEach { line ->
                    Log.d("vless-client-out", line)
                    errorOutput += line
                    config.onLogEntry?.invoke(line)
                }
            } catch (e: Exception) {
                Log.w(TAG, "vless-client output reader stopped", e)
            }
        }, "XrayMonitor").apply { start() }

        val ready = withTimeoutOrNull(START_TIMEOUT_MS) {
            waitForPortReady(config.listenPort)
        }

        if (ready != true) {
            var exitCode: Int
            try {
                exitCode = process.exitValue()
            } catch (e: IllegalThreadStateException) {
                exitCode = -1
                process.destroy()
            }
            val stderr = errorOutput.joinToString("\n").takeIf { it.isNotBlank() }
            val errMsg = if (exitCode != 0) {
                "vless-client exited with code $exitCode${stderr?.let { "\nstderr: $it" } ?: ""}"
            } else {
                "vless-client failed to start within ${START_TIMEOUT_MS}ms${stderr?.let { "\nstderr: $it" } ?: ""}"
            }
            Log.e(TAG, errMsg)
            _state.value = XrayEngineState(XrayEngine.STATE_ERROR, errMsg)
            cleanupProcess()
            workingDir.deleteRecursively()
            File(binaryPath).delete()
            return@withContext Result.failure(RuntimeException(errMsg))
        }

        val exitCheck = try {
            process.exitValue()
        } catch (e: IllegalThreadStateException) {
            null
        }

        if (exitCheck != null) {
            val stderr = errorOutput.joinToString("\n")
            val errMsg = "vless-client exited early: code=$exitCheck\nstderr: $stderr"
            Log.e(TAG, errMsg)
            _state.value = XrayEngineState(XrayEngine.STATE_ERROR, errMsg)
            cleanupProcess()
            workingDir.deleteRecursively()
            File(binaryPath).delete()
            return@withContext Result.failure(RuntimeException(errMsg))
        }

        Log.i(TAG, "vless-client started, SOCKS5 on ${config.listenPort}")
        _state.value = XrayEngineState(XrayEngine.STATE_RUNNING)
        workingDir.deleteRecursively()
        Result.success(Unit)
    }

    override suspend fun stopXray(): Result<Unit> = withContext(Dispatchers.IO) {
        val proc = xrayProcess ?: return@withContext Result.success(Unit)

        Log.i(TAG, "Stopping vless-client")
        _state.value = XrayEngineState(XrayEngine.STATE_STOPPING)

        try {
            proc.destroy()
            withTimeoutOrNull(STOP_TIMEOUT_MS) {
                proc.waitFor()
            }
            if (proc.isAlive) {
                proc.destroyForcibly()
                delay(500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vless-client", e)
        }

        cleanupProcess()
        _state.value = XrayEngineState(XrayEngine.STATE_IDLE)
        Result.success(Unit)
    }

    private suspend fun waitForPortReady(port: Int): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (xrayProcess?.isAlive == false) return@withContext false
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                    return@withContext true
                }
            } catch (e: Exception) {
                delay(PORT_POLL_MS)
            }
        }
        false
    }

    private fun cleanupProcess() {
        monitorThread?.let { t ->
            t.interrupt()
        }
        monitorThread = null
        xrayProcess = null
    }
}
