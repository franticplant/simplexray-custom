// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.common.configFormat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrojanLinkConverterTest {
    private val converter = TrojanLinkConverter()

    private fun convert(link: String): Pair<String, JSONObject> {
        val (name, json) = converter.convert(link, socksPort = 10808).getOrThrow()
        return name to JSONObject(json)
    }

    private fun JSONObject.outbound() = getJSONArray("outbounds").getJSONObject(0)

    private fun JSONObject.server() =
        outbound().getJSONObject("settings").getJSONArray("servers").getJSONObject(0)

    private fun JSONObject.stream() = outbound().getJSONObject("streamSettings")

    @Test
    fun defaultsToTls() {
        val (name, config) = convert("trojan://p%40ss@example.com:443?sni=sni.example.com#Tr")

        assertEquals("Tr", name)
        assertEquals("trojan", config.outbound().getString("protocol"))
        assertEquals("example.com", config.server().getString("address"))
        assertEquals(443, config.server().getInt("port"))
        assertEquals("p@ss", config.server().getString("password"))
        assertEquals("tls", config.stream().getString("security"))
        assertEquals(
            "sni.example.com",
            config.stream().getJSONObject("tlsSettings").getString("serverName"),
        )
    }

    @Test
    fun websocket_andExplicitNoSecurity() {
        val (_, config) =
            convert("trojan://pw@10.0.0.1:8080?type=ws&security=none&path=%2Fws&host=h.example")

        assertEquals("ws", config.stream().getString("network"))
        assertEquals("none", config.stream().getString("security"))
        assertEquals("/ws", config.stream().getJSONObject("wsSettings").getString("path"))
    }

    @Test
    fun rejectsMalformedLinks() {
        listOf("trojan://example.com:443", "trojan://pw@:443", "vless://pw@h:1").forEach { link ->
            assertTrue(link, converter.convert(link, socksPort = 1).isFailure)
        }
    }
}
