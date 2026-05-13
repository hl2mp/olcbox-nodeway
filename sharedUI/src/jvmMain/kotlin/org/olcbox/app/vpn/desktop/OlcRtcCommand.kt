package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.LocationConfig
import java.nio.file.Path

internal data class OlcRtcCommand(
    val binary: Path,
    val location: LocationConfig,
    val socksHost: String = PacServer.LOCAL_SOCKS_HOST,
    val socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
    val socksUser: String = "",
    val socksPass: String = "",
    val dataDir: Path? = null
) {
    fun args(): List<String> {
        val config = location.normalized()
        val provider = desktopProviderArg(config.bypassProvider)
        val baseArgs = listOf(
            binary.toString(),
            "-mode", "cnc",
            "-link", "direct",
            "-transport", config.transport,
            "-carrier", provider,
            "-id", config.id,
            "-client-id", config.clientId,
            "-key", config.key,
            "-socks-host", socksHost,
            "-socks-port", socksPort.toString(),
            "-dns", "1.1.1.1:53"
        ) + socksAuthArgs()
        val transportArgs = when (config.transport) {
            LocationConfig.TRANSPORT_VP8CHANNEL -> listOf(
                "-vp8-fps", config.vp8Fps.toString(),
                "-vp8-batch", config.vp8Batch.toString()
            )
            LocationConfig.TRANSPORT_SEICHANNEL -> listOf(
                "-fps", "60",
                "-batch", "64",
                "-frag", "900",
                "-ack-ms", "2000"
            )
            else -> emptyList()
        }
        return baseArgs + transportArgs + listOfNotNull(
            dataDir?.let { "-data" },
            dataDir?.toString()
        )
    }

    private fun socksAuthArgs(): List<String> {
        return if (socksUser.isBlank()) {
            emptyList()
        } else {
            listOf("-socks-user", socksUser, "-socks-pass", socksPass)
        }
    }

    companion object {
        fun desktopProviderArg(provider: String): String {
            val normalizedProvider = LocationConfig.normalizeProvider(provider)
            return when (normalizedProvider) {
                LocationConfig.PROVIDER_WB_STREAM -> "wbstream"
                else -> normalizedProvider
            }
        }
    }
}
