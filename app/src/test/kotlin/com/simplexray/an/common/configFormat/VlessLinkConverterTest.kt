// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.common.configFormat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessLinkConverterTest {
    private val converter = VlessLinkConverter()
    private val uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"

    private fun convert(link: String): Pair<String, JSONObject> {
        val (name, json) = converter.convert(link, socksPort = 10808).getOrThrow()
        return name to JSONObject(json)
    }

    private fun JSONObject.outbound() = getJSONArray("outbounds").getJSONObject(0)

    private fun JSONObject.stream() = outbound().getJSONObject("streamSettings")

    private fun JSONObject.vnext() =
        outbound().getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)

    private fun JSONObject.user() = vnext().getJSONArray("users").getJSONObject(0)

    @Test
    fun reality_tcp() {
        val (name, config) =
            convert(
                "vless://$uuid@example.com:443?security=reality&sni=www.microsoft.com" +
                    "&fp=firefox&pbk=PUBKEY&sid=ab12&type=tcp&flow=xtls-rprx-vision#My%20Server"
            )

        assertEquals("My Server", name)
        assertEquals("example.com", config.vnext().getString("address"))
        assertEquals(443, config.vnext().getInt("port"))
        assertEquals(uuid, config.user().getString("id"))
        assertEquals("xtls-rprx-vision", config.user().getString("flow"))
        val reality = config.stream().getJSONObject("realitySettings")
        assertEquals("www.microsoft.com", reality.getString("serverName"))
        assertEquals("firefox", reality.getString("fingerprint"))
        assertEquals("PUBKEY", reality.getString("publicKey"))
        assertEquals("ab12", reality.getString("shortId"))
        assertEquals(10808, config.getJSONArray("inbounds").getJSONObject(0).getInt("port"))
    }

    @Test
    fun reality_isInferredFromPublicKey_withVisionOverTcp() {
        val (_, config) = convert("vless://$uuid@example.com:443?pbk=PUBKEY&sid=ab")

        assertEquals("reality", config.stream().getString("security"))
        assertEquals("xtls-rprx-vision", config.user().getString("flow"))
    }

    @Test
    fun tls_websocket_keepsPathHostAndAlpn_andHasNoVisionFlow() {
        val (_, config) =
            convert(
                "vless://$uuid@cdn.example.com:443?type=ws&security=tls&sni=sni.example.com" +
                    "&host=ws.example.com&path=%2Fws%3Fed%3D2048&alpn=h2,http/1.1#ws"
            )

        val stream = config.stream()
        assertEquals("ws", stream.getString("network"))
        assertEquals("tls", stream.getString("security"))
        assertFalse(stream.has("realitySettings"))
        val tls = stream.getJSONObject("tlsSettings")
        assertEquals("sni.example.com", tls.getString("serverName"))
        assertEquals("h2", tls.getJSONArray("alpn").getString(0))
        assertEquals("http/1.1", tls.getJSONArray("alpn").getString(1))
        val ws = stream.getJSONObject("wsSettings")
        assertEquals("/ws?ed=2048", ws.getString("path"))
        assertEquals("ws.example.com", ws.getString("host"))
        assertFalse(config.user().has("flow"))
    }

    @Test
    fun grpc_serviceNameAndMultiMode() {
        val (_, config) =
            convert(
                "vless://$uuid@example.com:443?type=grpc&security=tls&serviceName=svc&mode=multi"
            )

        val grpc = config.stream().getJSONObject("grpcSettings")
        assertEquals("svc", grpc.getString("serviceName"))
        assertTrue(grpc.getBoolean("multiMode"))
        assertFalse(config.user().has("flow"))
    }

    @Test
    fun noSecurity_isNone() {
        val (_, config) = convert("vless://$uuid@10.0.0.1:8080?type=tcp")

        assertEquals("none", config.stream().getString("security"))
        assertFalse(config.stream().has("tlsSettings"))
        assertFalse(config.stream().has("realitySettings"))
    }

    @Test
    fun aliases_rawAndSplithttp() {
        assertEquals(
            "tcp",
            convert("vless://$uuid@h:1?type=raw").second.stream().getString("network"),
        )
        assertEquals(
            "xhttp",
            convert("vless://$uuid@h:1?type=splithttp&path=/x")
                .second
                .stream()
                .getString("network"),
        )
    }

    @Test
    fun ipv6Host_andDefaultPort() {
        val (_, v6) = convert("vless://$uuid@[2001:db8::1]:8443?security=none")
        assertEquals("2001:db8::1", v6.vnext().getString("address"))
        assertEquals(8443, v6.vnext().getInt("port"))

        val (_, noPort) = convert("vless://$uuid@example.com/?security=none")
        assertEquals(443, noPort.vnext().getInt("port"))
    }

    @Test
    fun lenient_aboutUnencodedNameCharacters() {
        val (name, _) = convert("vless://$uuid@example.com:443?security=none#DE 🇩🇪 node|1")

        assertEquals("DE 🇩🇪 node_1", name)
    }

    @Test
    fun name_isSanitizedForTheFileSystem() {
        assertEquals("a_b_c", VlessLinkConverter.sanitizeName("a/b\\c"))
        assertEquals(null, VlessLinkConverter.sanitizeName(" .. "))
        assertEquals(null, VlessLinkConverter.sanitizeName(null))
    }

    @Test
    fun missingNameGetsAGeneratedOne() {
        val (name, _) = convert("vless://$uuid@example.com:443?security=none")

        assertTrue(name.startsWith("imported_vless_"))
    }

    @Test
    fun rejectsMalformedLinks() {
        listOf(
                "vless://example.com:443",
                "vless://$uuid@:443",
                "vless://$uuid@example.com:99999",
                "vless://$uuid@[::1:443",
                "vmess://abc",
            )
            .forEach { link ->
                assertTrue(link, converter.convert(link, socksPort = 1).isFailure)
            }
    }

    @Test
    fun parseQuery_keepsPlusAndToleratesBadEscapes() {
        val q = VlessLinkConverter.parseQuery("pbk=a+b&path=%zz&flag")

        assertEquals("a+b", q["pbk"])
        assertEquals("%zz", q["path"])
        assertEquals("", q["flag"])
    }
}
