package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class WindowsTunController(
    private val addLog: (String) -> Unit
) {
    private var routesInstalled = false

    suspend fun start(
        hevBinary: Path,
        socksPort: Int = PacServer.LOCAL_SOCKS_PORT
    ): Process {
        requireAdministrator()

        val config = writeConfig(socksPort)
        val process = ProcessBuilder(hevBinary.toString(), config.toString())
            .redirectErrorStream(true)
            .start()

        try {
            waitForAdapter(process)
            installRoutes()
            routesInstalled = true
            addLog("Windows TUN connected on $TUN_NAME")
            return process
        } catch (e: Exception) {
            stop(process)
            throw e
        }
    }

    suspend fun stop(process: Process?) {
        if (routesInstalled) {
            runCatching { removeRoutes() }
                .onFailure { addLog("Windows TUN route cleanup failed: ${it.message}") }
            routesInstalled = false
        }

        stopProcess(process)
    }

    private suspend fun requireAdministrator() {
        val isAdmin = runPowerShell(
            """
            ${'$'}principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
            if (${'$'}principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { 'true' } else { 'false' }
            """.trimIndent()
        ).trim().equals("true", ignoreCase = true)

        if (!isAdmin) {
            error("Windows TUN mode requires running Olcbox as administrator")
        }
    }

    private fun writeConfig(socksPort: Int): Path {
        val config = DesktopPaths.appDataDir().resolve("windows-tun.yml")
        Files.writeString(config, configContent(socksPort))
        return config
    }

    private suspend fun waitForAdapter(process: Process) {
        val deadline = System.currentTimeMillis() + TUN_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                error(
                    buildString {
                        append("hev-socks5-tunnel exited before $TUN_NAME was ready")
                        if (output.isNotBlank()) append(": ").append(output)
                    }
                )
            }

            if (adapterExists()) return
            delay(TUN_READY_POLL_MS)
        }

        error("$TUN_NAME adapter was not created")
    }

    private suspend fun adapterExists(): Boolean {
        return runCatching {
            runPowerShell(
                """
                ${'$'}adapter = Get-NetAdapter -Name '$TUN_NAME' -ErrorAction SilentlyContinue
                if (${'$'}null -ne ${'$'}adapter) { 'true' } else { 'false' }
                """.trimIndent()
            ).trim().equals("true", ignoreCase = true)
        }.getOrDefault(false)
    }

    private suspend fun installRoutes() {
        runPowerShell(
            """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}adapter = Get-NetAdapter -Name '$TUN_NAME' -ErrorAction Stop
            ${'$'}ifIndex = ${'$'}adapter.ifIndex

            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '0.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '128.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue

            New-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '0.0.0.0/1' -NextHop '0.0.0.0' -RouteMetric 1 | Out-Null
            New-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '128.0.0.0/1' -NextHop '0.0.0.0' -RouteMetric 1 | Out-Null
            Set-DnsClientServerAddress -InterfaceIndex ${'$'}ifIndex -ServerAddresses '$MAPDNS_ADDRESS'
            """.trimIndent()
        )
    }

    private suspend fun removeRoutes() {
        runPowerShell(
            """
            ${'$'}adapter = Get-NetAdapter -Name '$TUN_NAME' -ErrorAction SilentlyContinue
            if (${'$'}null -eq ${'$'}adapter) { exit 0 }
            ${'$'}ifIndex = ${'$'}adapter.ifIndex
            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '0.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '128.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Set-DnsClientServerAddress -InterfaceIndex ${'$'}ifIndex -ResetServerAddresses -ErrorAction SilentlyContinue
            """.trimIndent()
        )
    }

    private suspend fun runPowerShell(script: String): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            script
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("PowerShell failed with code $exitCode: $output")
        }
        output
    }

    private fun stopProcess(process: Process?) {
        if (process == null || !process.isAlive) return
        process.toHandle().descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    internal companion object {
        const val TUN_NAME = "Olcbox"
        const val TUN_MTU = 1500
        const val TUN_IPV4_ADDRESS = "10.0.88.88"
        const val MAPDNS_ADDRESS = "1.1.1.1"
        const val MAPDNS_NETWORK = "100.64.0.0"
        const val MAPDNS_NETMASK = "255.192.0.0"
        const val TUN_READY_TIMEOUT_MS = 10_000L
        const val TUN_READY_POLL_MS = 100L
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L

        fun configContent(socksPort: Int = PacServer.LOCAL_SOCKS_PORT): String {
            return buildString {
                appendLine("tunnel:")
                appendLine("  name: $TUN_NAME")
                appendLine("  mtu: $TUN_MTU")
                appendLine("  multi-queue: false")
                appendLine("  ipv4: $TUN_IPV4_ADDRESS")
                appendLine()
                appendLine("socks5:")
                appendLine("  address: ${PacServer.LOCAL_SOCKS_HOST}")
                appendLine("  port: $socksPort")
                appendLine("  udp: 'tcp'")
                appendLine("  pipeline: false")
                appendLine()
                appendLine("mapdns:")
                appendLine("  address: $MAPDNS_ADDRESS")
                appendLine("  port: 53")
                appendLine("  network: $MAPDNS_NETWORK")
                appendLine("  netmask: $MAPDNS_NETMASK")
                appendLine("  cache-size: 10000")
                appendLine()
                appendLine("misc:")
                appendLine("  task-stack-size: 24576")
                appendLine("  tcp-buffer-size: 4096")
                appendLine("  max-session-count: 1200")
                appendLine("  connect-timeout: 10000")
                appendLine("  tcp-read-write-timeout: 300000")
                appendLine("  udp-read-write-timeout: 60000")
                appendLine("  log-file: stderr")
                appendLine("  log-level: warn")
            }.trimEnd()
        }
    }
}
