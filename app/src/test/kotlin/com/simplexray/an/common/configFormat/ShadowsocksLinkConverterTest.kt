// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.common.configFormat

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowsocksLinkConverterTest {
    private val converter = ShadowsocksLinkConverter()

    private fun convert(link: String): Pair<String, JSONObject> {
        val (name, json) = converter.convert(link, socksPort = 10808).getOrThrow()
        return name to JSONObject(json)
    }

    private fun b64(s: String) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    private fun JSONObject.outbound() = getJSONArray("outbounds").getJSONObject(0)

    private fun JSONObject.server() =
        outbound().getJSONObject("settings").getJSONArray("servers").getJSONObject(0)

    private fun assertServer(
        config: JSONObject,
        host: String,
        port: Int,
        method: String,
        password: String,
    ) {
        assertEquals("shadowsocks", config.outbound().getString("protocol"))
        assertEquals(host, config.server().getString("address"))
        assertEquals(port, config.server().getInt("port"))
        assertEquals(method, config.server().getString("method"))
        assertEquals(password, config.server().getString("password"))
        assertFalse(config.outbound().has("streamSettings"))
    }

    @Test
    fun sip002_base64UserInfo() {
        val (name, config) =
            convert("ss://${b64("chacha20-ietf-poly1305:p@ss:w")}@example.com:8388/#SS%20node")

        assertEquals("SS node", name)
        assertServer(config, "example.com", 8388, "chacha20-ietf-poly1305", "p@ss:w")
    }

    @Test
    fun sip002_percentEncodedPadding() {
        val padded = Base64.getEncoder().encodeToString("aes-256-gcm:pw".toByteArray())
        val (_, config) = convert("ss://${padded.replace("=", "%3D")}@1.2.3.4:443")

        assertServer(config, "1.2.3.4", 443, "aes-256-gcm", "pw")
    }

    @Test
    fun sip002_plainUserInfoFor2022Ciphers() {
        val key = "YctPZ6U7xPPcU+gp3u+0tx/tRizJN9K8y+uKlW2qjlI="
        val (_, config) =
            convert("ss://2022-blake3-aes-256-gcm:${key.replace("+", "%2B")}@[2001:db8::1]:443")

        assertServer(config, "2001:db8::1", 443, "2022-blake3-aes-256-gcm", key)
    }

    @Test
    fun legacy_wholeBodyBase64() {
        val (name, config) = convert("ss://${b64("aes-128-gcm:pw@legacy.example:8000")}#old")

        assertEquals("old", name)
        assertServer(config, "legacy.example", 8000, "aes-128-gcm", "pw")
    }

    @Test
    fun refusesPlugins() {
        val result =
            converter.convert(
                "ss://${b64("aes-256-gcm:pw")}@example.com:443/?plugin=obfs-local%3Bobfs%3Dhttp",
                socksPort = 1,
            )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("obfs-local"))
    }

    @Test
    fun rejectsMalformedLinks() {
        listOf(
                "ss://${b64("aes-256-gcm")}@example.com:443",
                "ss://${b64("aes-256-gcm:pw")}@example.com:0",
                "ss://${b64("no at sign")}",
                "ss://%%%@example.com:443",
            )
            .forEach { link -> assertTrue(link, converter.convert(link, socksPort = 1).isFailure) }
    }
}
