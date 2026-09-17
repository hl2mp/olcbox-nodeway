package org.olcbox.app.vpn

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.olcbox.app.vpn.service.OlcboxVpnState
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.model.VlessConfig
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

internal object XrayConnectionChecker {
    private const val START_TIMEOUT_MS = 20_000L
    private const val PORT_POLL_MS = 200L
    private const val STOP_TIMEOUT_MS = 1_000L
    private const val SOCKET_TIMEOUT_MS = 5_000

    suspend fun ping(
        context: Context,
        vlessConfig: VlessConfig,
        socksUsername: String = "",
        socksPassword: String = ""
    ): Long? {
        return withContext(Dispatchers.IO) {
            val vless = vlessConfig.normalized()
            OlcboxVpnState.addLog("ping: config complete=${vless.isComplete()}, server=${vless.server}, port=${vless.port}")
            if (!vless.isComplete()) {
                OlcboxVpnState.addLog("ping: config not complete")
                return@withContext null
            }

            val binaryFile = extractBinary(context)
            if (binaryFile == null) {
                OlcboxVpnState.addLog("ping: binary extraction failed")
                return@withContext null
            }
            OlcboxVpnState.addLog("ping: binary extracted to ${binaryFile.absolutePath}")

            val port = allocateLocalPort()
            var process: Process? = null

            try {
                val localAddress = resolveServerAddress(vless.server, vless.port)
                if (localAddress != null) {
                    OlcboxVpnState.addLog("ping: resolved ${vless.server} -> $localAddress")
                } else {
                    OlcboxVpnState.addLog("ping: DNS lookup failed for ${vless.server}")
                }

                process = startXray(
                    binaryFile = binaryFile,
                    vlessLink = vless.toUri(),
                    port = port,
                    socksUsername = socksUsername,
                    socksPassword = socksPassword,
                    localAddress = localAddress
                )
                if (process == null) {
                    OlcboxVpnState.addLog("ping: xray start failed")
                    return@withContext null
                }
                OlcboxVpnState.addLog("ping: xray started")

                val ready = waitForPort(port, process)
                if (!ready) {
                    OlcboxVpnState.addLog("ping: xray port not ready after ${START_TIMEOUT_MS}ms")
                    return@withContext null
                }
                OlcboxVpnState.addLog("ping: xray port ready")

                val latency = httpPingThroughSocks(port, socksUsername, socksPassword)
                OlcboxVpnState.addLog("ping: result latency=$latency")
                if (latency > 0) latency else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OlcboxVpnState.addLog("ping: exception=${e.message}")
                null
            } finally {
                process?.let { stopProcess(it) }
                binaryFile.delete()
            }
        }
    }

    private fun extractBinary(context: Context): File? {
        val libDir = context.applicationInfo.nativeLibraryDir
        val sourceFile = File(libDir, "libvless.so")
        if (!sourceFile.exists() || !sourceFile.canRead()) {
            OlcboxVpnState.addLog("ping: binary not found at ${sourceFile.absolutePath}")
            return null
        }

        val destFile = File(context.filesDir, "vless-${UUID.randomUUID()}")
        return try {
            sourceFile.copyTo(destFile)
            try {
                Os.chmod(destFile.absolutePath, 0x1ED)
            } catch (e: ErrnoException) {
                OlcboxVpnState.addLog("ping: Os.chmod failed, using file permissions fallback: $e")
                destFile.setExecutable(true, false)
                destFile.setWritable(true, false)
                destFile.setReadable(true, false)
            }
            destFile
        } catch (e: Exception) {
            OlcboxVpnState.addLog("ping: binary extraction failed: $e")
            destFile.delete()
            null
        }
    }

    private fun startXray(
        binaryFile: File,
        vlessLink: String,
        port: Int,
        socksUsername: String,
        socksPassword: String,
        localAddress: String? = null
    ): Process? {
        return try {
            val cmd = buildList {
                add("-link")
                add(vlessLink)
                add("-listen")
                add("127.0.0.1:$port")
                if (socksUsername.isNotBlank()) {
                    add("-proxy-user")
                    add(socksUsername)
                }
                if (socksPassword.isNotBlank()) {
                    add("-proxy-pass")
                    add(socksPassword)
                }
                add("-debug")
                if (localAddress != null) {
                    add("-local-address")
                    add(localAddress)
                }
            }

            val linker = findLinker()
val fullCmd = if (linker != null) {
            listOf(linker, binaryFile.absolutePath) + cmd
        } else {
            listOf(binaryFile.absolutePath) + cmd
        }
            OlcboxVpnState.addLog("ping: starting xray with linker=${linker ?: "direct"}")
            OlcboxVpnState.addLog("ping: fullCmd=${fullCmd.joinToString(" ")}")
            OlcboxVpnState.addLog( "ping: vlessLink=$vlessLink")

            val process = ProcessBuilder(fullCmd)
                .directory(binaryFile.parentFile)
                .redirectErrorStream(true)
                .apply {
                    environment()["GODEBUG"] = "netdns=go"
                }
                .start()
            drainProcessOutput(process)
            process
        } catch (e: Exception) {
            OlcboxVpnState.addLog("ping: failed to start xray: ${e.message}")
            null
        }
    }

    private fun resolveServerAddress(server: String, port: Int): String? {
        val ips = try {
            InetAddress.getAllByName(server).map { it.hostAddress }
        } catch (e: Exception) {
            OlcboxVpnState.addLog("ping: resolveServerAddress failed: ${e.message}")
            emptyList()
        }
        if (ips.isEmpty()) return null
        val ipv4 = ips.firstOrNull { !it.contains(":") } ?: ips.first()
        return "$ipv4:$port"
    }

    private fun findLinker(): String? {
        return listOf("/system/bin/linker64", "/system/bin/linker")
            .firstOrNull { File(it).exists() }
    }

    private fun drainProcessOutput(process: Process) {
        Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.forEachLine { line ->
                        OlcboxVpnState.addLog( "ping: xray: $line")
                    }
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private suspend fun waitForPort(port: Int, process: Process): Boolean {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) return false
            if (isPortOpen(port)) return true
            delay(PORT_POLL_MS)
        }
        return false
    }

    private fun isPortOpen(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 500)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun stopProcess(process: Process) {
        try {
            process.destroy()
            withTimeoutOrNull(STOP_TIMEOUT_MS) {
                process.waitFor()
            }
            if (process.isAlive) {
                process.destroyForcibly()
                withTimeoutOrNull(500) {
                    process.waitFor()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun httpPingThroughSocks(
        socksPort: Int,
        socksUsername: String,
        socksPassword: String
    ): Long {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), SOCKET_TIMEOUT_MS)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            val useAuth = socksUsername.isNotBlank() && socksPassword.isNotBlank()
            OlcboxVpnState.addLog( "ping: socks5 auth=$useAuth")

            if (useAuth) {
                output.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            } else {
                output.write(byteArrayOf(0x05, 0x01, 0x00))
            }
            output.flush()

            input.readUnsignedByte() // VER (should be 0x05)
            val method = input.readUnsignedByte()
            OlcboxVpnState.addLog( "ping: socks5 method=$method")
            if (method != 0x00 && method != 0x02) {
                return -1
            }

            if (useAuth && method == 0x02) {
                val userBytes = socksUsername.toByteArray()
                val passBytes = socksPassword.toByteArray()
                output.write(0x01)
                output.write(userBytes.size)
                output.write(userBytes)
                output.write(passBytes.size)
                output.write(passBytes)
                output.flush()

                if (input.readUnsignedByte() != 0x01 || input.readUnsignedByte() != 0x00) {
                    return -1
                }
            }

            val target = "www.google.com"
            val targetBytes = target.toByteArray()
            OlcboxVpnState.addLog( "ping: socks5 connect to $target:80")
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, targetBytes.size.toByte()))
            output.write(targetBytes)
            output.writeShort(80)
            output.flush()

            val ver = input.readUnsignedByte()
            val reply = input.readUnsignedByte()
            OlcboxVpnState.addLog( "ping: socks5 connect reply ver=$ver reply=$reply")
            if (reply != 0x00) {
                OlcboxVpnState.addLog( "ping: socks5 connect reply=$reply")
                return -1
            }
            input.readUnsignedByte()
            val addrType = input.readUnsignedByte()
            OlcboxVpnState.addLog( "ping: socks5 addrType=$addrType")
            when (addrType) {
                0x01 -> input.readFully(ByteArray(4))
                0x03 -> input.readFully(ByteArray(input.readUnsignedByte()))
                0x04 -> input.readFully(ByteArray(16))
                else -> return -1
            }
            input.readUnsignedShort()

            val startedAt = System.currentTimeMillis()
            OlcboxVpnState.addLog( "ping: sending HTTP GET")
            output.write("GET /generate_204 HTTP/1.1\r\nHost: www.google.com\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()

            val statusLine = BufferedReader(InputStreamReader(input)).readLine()
            OlcboxVpnState.addLog( "ping: statusLine=$statusLine")
            if (statusLine == null) return -1
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
            OlcboxVpnState.addLog( "ping: statusCode=$code")
            if (code != null && code in 200..399) System.currentTimeMillis() - startedAt else -1
        } catch (e: Exception) {
            OlcboxVpnState.addLog( "ping: socket operation failed=${e.message}")
            -1
        } finally {
            socket.close()
        }
    }

    private fun allocateLocalPort(): Int {
        return ServerSocket(0).use { it.localPort }
    }
}
