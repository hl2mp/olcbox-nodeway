package org.olcbox.app.vpn.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class PacServer(
    private val host: String = PAC_HOST,
    private val port: Int = PAC_PORT
) {
    private var server: HttpServer? = null
    @Volatile
    private var socksTarget = SocksTarget(LOCAL_SOCKS_HOST, LOCAL_SOCKS_PORT)
    private var executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OlcboxPacServer").apply { isDaemon = true }
    }

    val url: String
        get() = "http://$host:$port/proxy.pac"

    fun start(
        socksHost: String = LOCAL_SOCKS_HOST,
        socksPort: Int = LOCAL_SOCKS_PORT
    ) {
        updateSocksTarget(socksHost, socksPort)
        if (server != null) return
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "OlcboxPacServer").apply { isDaemon = true }
        }
        server = HttpServer.create(InetSocketAddress(host, port), 0).also { httpServer ->
            httpServer.createContext("/proxy.pac") { exchange ->
                exchange.respond(currentPacContent())
            }
            httpServer.createContext("/") { exchange ->
                exchange.respond(currentPacContent())
            }
            httpServer.executor = executor
            httpServer.start()
        }
    }

    fun updateSocksTarget(
        socksHost: String = LOCAL_SOCKS_HOST,
        socksPort: Int = LOCAL_SOCKS_PORT
    ) {
        socksTarget = SocksTarget(
            host = socksHost.ifBlank { LOCAL_SOCKS_HOST },
            port = socksPort
        )
    }

    internal fun currentPacContent(): String {
        val target = socksTarget
        return generatePac(target.host, target.port)
    }

    fun stop() {
        server?.stop(0)
        server = null
        executor.shutdownNow()
    }

    private fun HttpExchange.respond(body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/x-ns-proxy-autoconfig; charset=utf-8")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private data class SocksTarget(
        val host: String,
        val port: Int
    )

    companion object {
        const val PAC_HOST = "127.0.0.1"
        const val PAC_PORT = 10809
        const val LOCAL_SOCKS_HOST = "127.0.0.1"
        const val LOCAL_SOCKS_PORT = 10808

        fun generatePac(socksHost: String = LOCAL_SOCKS_HOST, socksPort: Int = LOCAL_SOCKS_PORT): String {
            return """
                function FindProxyForURL(url, host) {
                  if (isPlainHostName(host)) return "DIRECT";
                  if (host == "localhost" || host == "127.0.0.1" || host == "::1") return "DIRECT";
                  if (shExpMatch(host, "127.*")) return "DIRECT";
                  return "SOCKS5 $socksHost:$socksPort; SOCKS $socksHost:$socksPort";
                }
            """.trimIndent()
        }
    }
}
