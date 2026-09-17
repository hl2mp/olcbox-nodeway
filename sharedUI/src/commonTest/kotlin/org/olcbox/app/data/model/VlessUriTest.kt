package org.olcbox.app.data.model

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VlessUriTest {
    @Test
    fun parsesTcpWithTls() {
        val cfg = VlessUri.parse(
            "vless://2d2a2b3a-4c5e-6f70-8192-3a4b5c6d7e8f@example.com:443" +
                "?type=tcp&security=tls&sni=example.com&allowInsecure=1#My%20Server"
        )
        assertNotNull(cfg)
        assertEquals("example.com", cfg.server)
        assertEquals(443, cfg.port)
        assertEquals("2d2a2b3a-4c5e-6f70-8192-3a4b5c6d7e8f", cfg.uuid)
        assertEquals(VlessConfig.NETWORK_TCP, cfg.network)
        assertEquals(VlessConfig.SECURITY_TLS, cfg.security)
        assertEquals("example.com", cfg.sni)
        assertTrue(cfg.allowInsecure)
        assertEquals("My Server", cfg.name)
        assertTrue(cfg.isComplete())
    }

    @Test
    fun parsesWebsocketWithHostPathAndHeaders() {
        val cfg = VlessUri.parse(
            "vless://2d2a2b3a-4c5e-6f70-8192-3a4b5c6d7e8f@1.2.3.4:80" +
                "?type=ws&host=cdn.example.com&path=%2F&security=tls&sni=example.com&#header"
        )
        assertNotNull(cfg)
        assertEquals(VlessConfig.NETWORK_WS, cfg.network)
        assertEquals(VlessConfig.SECURITY_TLS, cfg.security)
        assertEquals("cdn.example.com", cfg.host)
        assertEquals("/", cfg.path)
        assertEquals("header", cfg.name)
    }

    @Test
    fun parsesGrpcWithServiceAndAuthority() {
        val cfg = VlessUri.parse(
            "vless://2d2a2b3a-4c5e-6f70-8192-3a4b5c6d7e8f@[::1]:443" +
                "?type=grpc&serviceName=proxy.example.Service&authority=cdn.example.com" +
                "&security=tls&sni=example.com#gRPC"
        )
        assertNotNull(cfg)
        assertEquals("::1", cfg.server)
        assertEquals(443, cfg.port)
        assertEquals(VlessConfig.NETWORK_GRPC, cfg.network)
        assertEquals("proxy.example.Service", cfg.grpcServiceName)
        assertEquals("cdn.example.com", cfg.grpcAuthority)
        assertEquals("gRPC", cfg.name)
    }

    @Test
    fun parsesRealityAndXtls() {
        val cfg = VlessUri.parse(
            "vless://2d2a2b3a-4c5e-6f70-8192-3a4b5c6d7e8f@example.com:443" +
                "?security=xtls&sni=example.com&flow=xtls-rprx-vision" +
                "&pbk=publickey123&pbn=abcdef&fp=chrome#Reality"
        )
        assertNotNull(cfg)
        assertEquals(VlessConfig.SECURITY_XTLS, cfg.security)
        assertEquals("xtls-rprx-vision", cfg.flow)
        assertEquals("publickey123", cfg.realityPublicKey)
        assertEquals("abcdef", cfg.realityShortId)
        assertEquals("chrome", cfg.fingerprint)
    }

    @Test
    fun normalizesSecurityAndNetworkAliases() {
        val cfg = VlessUri.parse(
            "vless://2d2a2b3a-4c5e-6f70-8192-3a4b5c6d7e8f@example.com:443" +
                "?network=ws&tls=1&sni=example.com"
        )
        assertNotNull(cfg)
        assertEquals(VlessConfig.NETWORK_WS, cfg.network)
        assertEquals(VlessConfig.SECURITY_TLS, cfg.security)
    }

    @Test
    fun rejectsInvalidUuidAndBadPort() {
        assertNull(
            VlessUri.parse(
                "vless://not-a-uuid@example.com:443?type=tcp#bad"
            )
        )
        assertNull(
            VlessUri.parse(
                "vless://2d2a2b3a-4c5e-6f70-8192-3a4b5c6d7e8f@example.com:99999?type=tcp"
            )
        )
        assertNull(
            VlessUri.parse(
                "vless://2d2a2b3a-4c5e-6f70-8192-3a4b5c6d7e8f@example.com?type=tcp"
            )
        )
    }

    @Test
    fun isCompleteFalseWhenMissingSniForTls() {
        val cfg = VlessUri.parse(
            "vless://2d2a2b3a-4c5e-6f70-8192-3a4b5c6d7e8f@example.com:443?type=tcp&security=tls"
        )
        assertNull(cfg)
    }

    @Test
    fun rejectsNonVlessSchemes() {
        assertNull(VlessUri.parse("vmess://abcd@example.com:443"))
        assertNull(VlessUri.parse("https://example.com"))
        assertNull(VlessUri.parse("olcrtc://wbstream?vp8channel@room#${"a".repeat(64)}"))
    }

    @Test
    fun rejectsInvalidTlsForGrpcWithoutService() {
        val cfg = VlessUri.parse(
            "vless://2d2a2b3a-4c5e-6f70-8192-3a4b5c6d7e8f@example.com:443?type=grpc"
        )
        assertNull(cfg)
    }

    @Test
    fun parseSubscriptionPlainMixedList() {
        val text = """
            vless://2d2a2b3a-4c5e-6f70-8192-3a4b5c6d7e8f@vless.example:443?type=ws&security=tls&sni=vless.example#VLESS
            olcrtc://wbstream?vp8channel@room-a#${"a".repeat(64)}%android-Alpha
        """.trimIndent()
        val urls = VlessUri.parseSubscription(text)
        assertEquals(1, urls.size)
        assertEquals(
            "vless://2d2a2b3a-4c5e-6f70-8192-3a4b5c6d7e8f@vless.example:443?type=ws&security=tls&sni=vless.example#VLESS",
            urls.single()
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun parseSubscriptionDecodesBase64() {
        val plain = """
            vless://2d2a2b3a-4c5e-6f70-8192-3a4b5c6d7e8f@sub.example:443?type=tcp&security=tls&sni=sub.example#B64
            olcrtc://wbstream?vp8channel@room#${"a".repeat(64)}${'$'}Base64
        """.trimIndent()
        val encoded = Base64.Default.encode(plain.encodeToByteArray())
        val urls = VlessUri.parseSubscription(encoded)
        assertEquals(1, urls.size)
        assertTrue(urls.single().startsWith(VlessUri.PREFIX))
        assertTrue("B64" in urls.single())
    }

    @Test
    fun subscriptionIgnoresUnrecognizedBody() {
        assertEquals(emptyList(), VlessUri.parseSubscription("this is not a subscription body"))
        assertEquals(emptyList(), VlessUri.parseSubscription(""))
    }

    @Test
    fun vlessNormalizedPreservesAndCleansFields() {
        val cfg = VlessUri.parse(
            "vless://2d2a2b3a-4c5e-6f70-8192-3a4b5c6d7e8f@[2001:db8::1]:443" +
                "?type=ws&sni=example.com&path=%2Ftest&allowinsecure=true#  Spaced  "
        )
        assertNotNull(cfg)
        val n = cfg.normalized()
        assertEquals("2001:db8::1", n.server)
        assertEquals("example.com", n.sni)
        assertEquals("/test", n.path)
        assertTrue(n.allowInsecure)
        assertEquals("Spaced", n.name)
        assertTrue(n.isComplete())
    }

    @Test
    fun toUriRoundTripsThroughParser() {
        val original = VlessConfig(
            server = "example.com",
            port = 443,
            uuid = "2d2a2b3a-4c5e-6f70-8192-3a4b5c6d7e8f",
            name = "Round Trip",
            network = VlessConfig.NETWORK_WS,
            security = VlessConfig.SECURITY_TLS,
            sni = "example.com",
            host = "example.com",
            path = "/ws",
            allowInsecure = true
        )
        val reparsed = VlessUri.parse(original.toUri())
        assertNotNull(reparsed)
        assertEquals(original.server, reparsed.server)
        assertEquals(original.port, reparsed.port)
        assertEquals(original.uuid, reparsed.uuid)
        assertEquals(original.network, reparsed.network)
        assertEquals(original.security, reparsed.security)
        assertEquals(original.sni, reparsed.sni)
        assertEquals(original.path, reparsed.path)
        assertEquals("Round Trip", reparsed.name)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun parsesCyrillicFragment() {
        val cfg = VlessUri.parse(
            "vless://073e17e9-dd90-43dc-a518-1ce156b197b7@jeri.hl2mp.ru:443" +
                "?type=tcp&security=tls&sni=jeri.hl2mp.ru&flow=xtls-rprx-vision&fp=firefox" +
                "#CF%20%D0%9E%D1%81%D0%BD%D0%BE%D0%B2%D0%BD%D1%8E%D1%8E%20%28TLS%29"
        )
        assertNotNull(cfg)
        assertEquals("CF Основнюю (TLS)", cfg.name)
        assertEquals("xtls-rprx-vision", cfg.flow)
        assertEquals("firefox", cfg.fingerprint)
    }
}
