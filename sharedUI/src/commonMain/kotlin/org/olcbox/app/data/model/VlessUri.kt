package org.olcbox.app.data.model

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object VlessUri {
    const val PREFIX = "vless://"
    private const val PORT_MAX = 65535

    fun parse(raw: String): VlessConfig? = runCatching {
        val trimmed = raw.trim()
        if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return null
        val payload = trimmed.substring(PREFIX.length)

        val fragment = payload.substringAfter("#", missingDelimiterValue = "").trim()
        val withoutFragment = payload.substringBefore("#")
        val split = withoutFragment.split("?", limit = 2)
        val base = split[0]
        val query = if (split.size == 2) split[1] else ""

        val at = base.indexOf("@")
        if (at < 0) return null
        val uuid = base.substring(0, at).trim()
        val authority = base.substring(at + 1).trim()

        if (!VlessConfig.isValidUuid(uuid)) return null

        val (host, port) = parseHostPort(authority) ?: return null
        if (host.isBlank()) return null

        val params = parseQuery(query)
        val config = buildConfig(uuid, host, port, fragment, params)
        config.normalized().takeIf { it.isComplete() }
    }.getOrNull()

    fun parseSubscription(text: String): List<String> {
        val normalized = text.trim()
        if (normalized.isEmpty()) return emptyList()

        val candidate = if (normalized.contains(
                Regex("vless://|vmess://|trojan://|ss://|olcrtc://|snell://", RegexOption.IGNORE_CASE))
        ) {
            normalized
        } else {
            VlessUri.decodeBase64(normalized) ?: return emptyList()
        }

        return candidate.lineSequence()
            .map { it.trim() }
            .filter { line -> line.startsWith(PREFIX, ignoreCase = true) }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun parseHostPort(authority: String): Pair<String, Int>? {
        val host: String
        val port: Int
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(1, close)
            val rest = authority.substring(close + 1)
            if (!rest.startsWith(":")) return null
            port = rest.substring(1).trim().toIntOrNull() ?: return null
        } else {
            val lastColon = authority.lastIndexOf(':')
            if (lastColon < 0) return null
            host = authority.substring(0, lastColon).trim()
            port = authority.substring(lastColon + 1).trim().toIntOrNull() ?: return null
        }
        if (host.isEmpty() || port !in 1..PORT_MAX) return null
        return host to port
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (part in query.split("&")) {
            if (part.isEmpty()) continue
            val eq = part.indexOf("=")
            val key = if (eq < 0) part else part.substring(0, eq)
            val value = if (eq < 0) "" else part.substring(eq + 1)
            result[percentDecode(key).lowercase()] = percentDecode(value)
        }
        return result
    }

    private fun buildConfig(
        uuid: String,
        host: String,
        port: Int,
        name: String,
        params: Map<String, String>
    ): VlessConfig {
        val network = params["type"] ?: params["network"] ?: VlessConfig.NETWORK_TCP
        val securityParam = params["security"] ?: params["tls"] ?: VlessConfig.SECURITY_NONE
        val security = resolveSecurity(securityParam, params)

        return VlessConfig(
            server = host,
            port = port,
            uuid = uuid,
            name = percentDecode(name),
            network = network,
            security = security,
            sni = params["sni"] ?: params["servername"],
            host = params["host"],
            path = params["path"]?.takeIf { it.isNotEmpty() },
            grpcServiceName = params["servicename"] ?: params["svc"],
            grpcAuthority = params["authority"] ?: params["group"],
            allowInsecure = parseBool(params["allowinsecure"] ?: params["allow_insecure"]),
            udp = parseBool(params["udp"]) || parseBool(params["udptunnel"]),
            mux = parseBool(params["mux"]),
            flow = params["flow"]?.takeIf { it.isNotEmpty() },
            fingerprint = params["fp"] ?: params["fingerprint"],
            realityPublicKey = params["pbk"] ?: params["publickey"] ?: params["realitypublickey"],
            realityShortId = params["pbn"] ?: params["shortid"] ?: params["realityshortid"],
            realityPackageName = params["packagename"] ?: params["package_name"],
            quicSecurity = params["quic"],
            kcpHeaderType = params["headertype"] ?: params["header_type"],
            kcpSeed = params["seed"],
            wsHeaders = parseHeaders(params["headers"] ?: params["head"] ?: params["wsheaders"] ?: "")
        )
    }

    private fun resolveSecurity(securityParam: String, params: Map<String, String>): String {
        val normalized = VlessConfig.normalizeSecurity(securityParam)
        if (normalized != VlessConfig.SECURITY_NONE) return normalized
        if (parseBool(params["tls"])) return VlessConfig.SECURITY_TLS
        if (parseBool(params["xtls"])) return VlessConfig.SECURITY_XTLS
        if (parseBool(params["reality"])) return VlessConfig.SECURITY_REALITY
        return VlessConfig.SECURITY_NONE
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (entry in raw.split(",")) {
            val colon = entry.indexOf(":")
            if (colon < 0) continue
            val k = entry.substring(0, colon).trim()
            val v = entry.substring(colon + 1).trim()
            if (k.isNotEmpty()) result[k] = v
        }
        return result
    }

    private fun parseBool(value: String?): Boolean {
        return when ((value ?: "").trim().lowercase()) {
            "1", "true", "yes", "on" -> true
            else -> false
        }
    }

    private fun percentDecode(value: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '%' && i + 2 < value.length &&
                    value[i + 1].digitToIntOrNull(16) != null &&
                    value[i + 2].digitToIntOrNull(16) != null -> {
                    val byte = (value[i + 1].digitToInt(16) shl 4) or value[i + 2].digitToInt(16)
                    bytes.add(byte.toByte())
                    i += 3
                }
                c == '+' -> {
                    bytes.add(' '.code.toByte())
                    i += 1
                }
                else -> {
                    val strBytes = c.toString().encodeToByteArray()
                    strBytes.forEach { bytes.add(it) }
                    i += 1
                }
            }
        }
        val decoded = bytes.toByteArray()
        return decoded.decodeToString()
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decodeBase64(text: String): String? {
        val cleaned = text.replace("\n", "").replace("\r", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        val attempts = listOf(Base64.Default, Base64.UrlSafe)
        for (base64 in attempts) {
            val decoded = runCatching { base64.decode(cleaned).decodeToString() }.getOrNull() ?: continue
            if (decoded.contains(PREFIX, ignoreCase = true) ||
                decoded.contains("vless://", ignoreCase = true) ||
                decoded.contains("vmess://", ignoreCase = true) ||
                decoded.contains("olcrtc://", ignoreCase = true)
            ) {
                return decoded
            }
        }
        return null
    }
}
