package org.olcbox.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VlessConfig(
    @SerialName("server")
    val server: String = "",
    @SerialName("port")
    val port: Int = 0,
    @SerialName("uuid")
    val uuid: String = "",
    @SerialName("name")
    val name: String = "",
    @SerialName("network")
    val network: String = VlessConfig.NETWORK_TCP,
    @SerialName("security")
    val security: String = VlessConfig.SECURITY_NONE,
    @SerialName("sni")
    val sni: String? = null,
    @SerialName("host")
    val host: String? = null,
    @SerialName("path")
    val path: String? = null,
    @SerialName("grpcServiceName")
    val grpcServiceName: String? = null,
    @SerialName("grpcAuthority")
    val grpcAuthority: String? = null,
    @SerialName("allowInsecure")
    val allowInsecure: Boolean = false,
    @SerialName("udp")
    val udp: Boolean = false,
    @SerialName("mux")
    val mux: Boolean = false,
    @SerialName("flow")
    val flow: String? = null,
    @SerialName("fingerprint")
    val fingerprint: String? = null,
    @SerialName("realityPublicKey")
    val realityPublicKey: String? = null,
    @SerialName("realityShortId")
    val realityShortId: String? = null,
    @SerialName("realityPackageName")
    val realityPackageName: String? = null,
    @SerialName("quicSecurity")
    val quicSecurity: String? = null,
    @SerialName("kcpHeaderType")
    val kcpHeaderType: String? = null,
    @SerialName("kcpSeed")
    val kcpSeed: String? = null,
    @SerialName("wsHeaders")
    val wsHeaders: Map<String, String> = emptyMap(),
    @SerialName("mode")
    val mode: String? = null,
    @SerialName("encryption")
    val encryption: String? = null,
    @SerialName("rawLink")
    val rawLink: String? = null
) {
    fun normalized(): VlessConfig {
        val normalizedServer = server.trim().removeSurrounding("[", "]")
        val normalizedNetwork = normalizeNetwork(network)
        val normalizedSecurity = normalizeSecurity(security)
        return copy(
            server = normalizedServer,
            port = if (port > 0) port else 0,
            uuid = uuid.trim(),
            name = name.trim(),
            network = normalizedNetwork,
            security = normalizedSecurity,
            sni = sni?.trim()?.takeIf { it.isNotEmpty() },
            host = host?.trim()?.takeIf { it.isNotEmpty() },
            path = path?.takeIf { it.isNotEmpty() },
            grpcServiceName = grpcServiceName?.trim()?.takeIf { it.isNotEmpty() },
            grpcAuthority = grpcAuthority?.trim()?.takeIf { it.isNotEmpty() },
            realityPublicKey = realityPublicKey?.trim()?.takeIf { it.isNotEmpty() },
            realityShortId = realityShortId?.trim()?.takeIf { it.isNotEmpty() },
            realityPackageName = realityPackageName?.trim()?.takeIf { it.isNotEmpty() },
            quicSecurity = quicSecurity?.trim()?.takeIf { it.isNotEmpty() },
            kcpHeaderType = kcpHeaderType?.trim()?.takeIf { it.isNotEmpty() },
            kcpSeed = kcpSeed?.trim()?.takeIf { it.isNotEmpty() },
            flow = flow?.trim()?.takeIf { it.isNotEmpty() },
            fingerprint = fingerprint?.trim()?.takeIf { it.isNotEmpty() },
            mode = mode?.trim()?.takeIf { it.isNotEmpty() },
            encryption = encryption?.trim()?.takeIf { it.isNotEmpty() },
            rawLink = rawLink?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    fun isComplete(): Boolean {
        val normalized = normalized()
        val hasServer = normalized.server.isNotBlank()
        val hasPort = normalized.port in 1..65535
        val hasUuid = isValidUuid(normalized.uuid)
        val tlsRequiresSniOrHost = when (normalized.security) {
            SECURITY_TLS, SECURITY_XTLS, SECURITY_REALITY -> true
            else -> false
        }
        val tlsReady = !tlsRequiresSniOrHost || normalized.sni != null || normalized.host != null
        val grpcNeedsService = normalized.network == NETWORK_GRPC && normalized.grpcServiceName == null
        return hasServer && hasPort && hasUuid && tlsReady && !grpcNeedsService
    }

    fun displayName(): String = name.takeIf { it.isNotBlank() }
        ?: "$server:$port"

    fun serverEndpoint(): String = if (server.contains(':') && !server.startsWith("[")) {
        "[$server]:$port"
    } else {
        "$server:$port"
    }

    override fun toString(): String = "VlessConfig(server=$server, port=$port, uuid=${uuid.take(8)}..., network=$network, security=$security, sni=$sni)"

    fun toUri(resolvedIp: String? = null): String {
        rawLink?.let { raw ->
            if (resolvedIp != null && resolvedIp.isNotBlank()) {
                val endpoint = if (resolvedIp.contains(':') && !resolvedIp.startsWith("[")) {
                    "[$resolvedIp]:$port"
                } else {
                    "$resolvedIp:$port"
                }
                val prefix = "$PREFIX$uuid@"
                if (raw.startsWith(prefix, ignoreCase = true)) {
                    val rest = raw.substring(prefix.length)
                    val slashIdx = rest.indexOf('/')
                    val qIdx = rest.indexOf('?')
                    val cut = listOf(slashIdx, qIdx).filter { it >= 0 }.minOrNull() ?: rest.length
                    return prefix + endpoint + rest.substring(cut)
                }
            }
            return raw
        }
        val resolvedServer = resolvedIp?.takeIf { it.isNotBlank() } ?: server
        val endpoint = if (resolvedServer.contains(':') && !resolvedServer.startsWith("[")) {
            "[$resolvedServer]:$port"
        } else {
            "$resolvedServer:$port"
        }
        val params = mutableListOf<String>()
        fun add(value: String?, key: String) {
            if (!value.isNullOrBlank()) params += "$key=$value"
        }
        params += "type=$network"
        if (security != SECURITY_NONE) params += "security=$security"
        add(sni, "sni")
        add(host, "host")
        add(path, "path")
        add(grpcServiceName, "serviceName")
        if (allowInsecure) params += "allowInsecure=1"
        if (udp) params += "udp=1"
        if (mux) params += "mux=1"
        add(flow, "flow")
        add(fingerprint, "fp")
        add(realityPublicKey, "pbk")
        add(realityShortId, "sid")
        add(realityPackageName, "spx")
        add(mode, "mode")
        add(encryption, "encryption")
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        val fragment = if (name.isNotBlank()) "#${encodeUriFragment(name)}" else ""
        return "$PREFIX$uuid@$endpoint$query$fragment"
    }

    companion object {
        const val PREFIX = "vless://"
        const val NETWORK_TCP = "tcp"
        const val NETWORK_WS = "ws"
        const val NETWORK_HTTP = "http"
        const val NETWORK_KCP = "kcp"
        const val NETWORK_GRPC = "grpc"
        const val NETWORK_HTTP2 = "h2"
        const val NETWORK_SCTP = "sctp"
        const val NETWORK_QUIC = "quic"
        const val NETWORK_XHTTP = "xhttp"

        const val SECURITY_NONE = "none"
        const val SECURITY_TLS = "tls"
        const val SECURITY_XTLS = "xtls"
        const val SECURITY_REALITY = "reality"

        val supportedNetworks = listOf(
            NETWORK_TCP, NETWORK_WS, NETWORK_HTTP, NETWORK_KCP,
            NETWORK_GRPC, NETWORK_HTTP2, NETWORK_SCTP, NETWORK_QUIC, NETWORK_XHTTP
        )

        val supportedSecurity = listOf(SECURITY_NONE, SECURITY_TLS, SECURITY_XTLS, SECURITY_REALITY)

        fun normalizeNetwork(value: String): String {
            return when (value.trim().lowercase()) {
                NETWORK_TCP, "websocket-tcp", "websocket" -> NETWORK_TCP
                NETWORK_WS, "websocket", "ws" -> NETWORK_WS
                NETWORK_HTTP, "http", "http1" -> NETWORK_HTTP
                NETWORK_KCP, "kcp", "mkcp", "mkcp" -> NETWORK_KCP
                NETWORK_GRPC, "grpc", "grpc-web" -> NETWORK_GRPC
                NETWORK_HTTP2, "h2", "http2" -> NETWORK_HTTP2
                NETWORK_SCTP -> NETWORK_SCTP
                NETWORK_QUIC -> NETWORK_QUIC
                NETWORK_XHTTP, "v2ray-xhttp" -> NETWORK_XHTTP
                else -> NETWORK_TCP
            }
        }

        fun normalizeSecurity(value: String): String {            return when (value.trim().lowercase()) {
                SECURITY_NONE, "", "plaintext", "raw" -> SECURITY_NONE
                SECURITY_TLS -> SECURITY_TLS
                SECURITY_XTLS -> SECURITY_XTLS
                SECURITY_REALITY -> SECURITY_REALITY
                else -> SECURITY_NONE
            }
        }

        fun isValidUuid(value: String): Boolean {
            return UUID_REGEX.matches(value.trim())
        }

        private val UUID_REGEX = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\$"
        )
    }
}

private fun encodeUriFragment(value: String): String {
    return value.replace("#", "%23").replace("?", "%3F")
}
