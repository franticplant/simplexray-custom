// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.common.configFormat

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VmessLinkConverterTest {
    private val converter = VmessLinkConverter()
    private val uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"

    private fun convert(link: String): Pair<String, JSONObject> {
        val (name, json) = converter.convert(link, socksPort = 10808).getOrThrow()
        return name to JSONObject(json)
    }

    private fun v2rayN(json: JSONObject, encoder: Base64.Encoder = Base64.getEncoder()) =
        "vmess://" + encoder.encodeToString(json.toString().toByteArray())

    private fun JSONObject.outbound() = getJSONArray("outbounds").getJSONObject(0)

    private fun JSONObject.stream() = outbound().getJSONObject("streamSettings")

    private fun JSONObject.vnext() =
        outbound().getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)

    private fun JSONObject.user() = vnext().getJSONArray("users").getJSONObject(0)

    @Test
    fun v2rayNJson_wsTls() {
        val (name, config) =
            convert(
                v2rayN(
                    JSONObject()
                        .put("v", "2")
                        .put("ps", "JP node")
                        .put("add", "cdn.example.com")
                        .put("port", "8443")
                        .put("id", uuid)
                        .put("aid", "0")
                        .put("scy", "aes-128-gcm")
                        .put("net", "ws")
                        .put("type", "none")
                        .put("host", "ws.example.com")
                        .put("path", "/vm")
                        .put("tls", "tls")
                        .put("sni", "sni.example.com")
                        .put("alpn", "h2,http/1.1")
                )
            )

        assertEquals("JP node", name)
        assertEquals("vmess", config.outbound().getString("protocol"))
        assertEquals("cdn.example.com", config.vnext().getString("address"))
        assertEquals(8443, config.vnext().getInt("port"))
        assertEquals(uuid, config.user().getString("id"))
        assertEquals("aes-128-gcm", config.user().getString("security"))
        assertFalse(config.user().has("alterId"))
        val stream = config.stream()
        assertEquals("ws", stream.getString("network"))
        assertEquals("tls", stream.getString("security"))
        assertEquals("sni.example.com", stream.getJSONObject("tlsSettings").getString("serverName"))
        assertEquals("/vm", stream.getJSONObject("wsSettings").getString("path"))
        assertEquals("ws.example.com", stream.getJSONObject("wsSettings").getString("host"))
    }

    @Test
    fun v2rayNJson_numericPort_urlSafeUnpadded_tcpHttpHeader() {
        val (_, config) =
            convert(
                v2rayN(
                    JSONObject()
                        .put("add", "1.2.3.4")
                        .put("port", 80)
                        .put("id", uuid)
                        .put("net", "tcp")
                        .put("type", "http")
                        .put("host", "a.example")
                        .put("path", "/")
                        .put("tls", ""),
                    Base64.getUrlEncoder().withoutPadding(),
                )
            )

        assertEquals(80, config.vnext().getInt("port"))
        assertEquals("auto", config.user().getString("security"))
        assertEquals("none", config.stream().getString("security"))
        val header = config.stream().getJSONObject("tcpSettings").getJSONObject("header")
        assertEquals("http", header.getString("type"))
    }

    @Test
    fun v2rayNJson_grpcModeComesFromType() {
        val (name, config) =
            convert(
                v2rayN(
                    JSONObject()
                        .put("add", "example.com")
                        .put("port", "443")
                        .put("id", uuid)
                        .put("net", "grpc")
                        .put("type", "multi")
                        .put("path", "svc")
                        .put("tls", "tls")
                )
            )

        val grpc = config.stream().getJSONObject("grpcSettings")
        assertEquals("svc", grpc.getString("serviceName"))
        assertTrue(grpc.getBoolean("multiMode"))
        assertTrue(name.startsWith("imported_vmess_"))
    }

    @Test
    fun uriForm() {
        val (name, config) =
            convert(
                "vmess://$uuid@example.com:443?encryption=chacha20-poly1305&type=grpc" +
                    "&security=tls&serviceName=svc#URI%20node"
            )

        assertEquals("URI node", name)
        assertEquals("chacha20-poly1305", config.user().getString("security"))
        assertEquals("grpc", config.stream().getString("network"))
        assertEquals(
            "svc",
            config.stream().getJSONObject("grpcSettings").getString("serviceName"),
        )
    }

    @Test
    fun rejectsMalformedLinks() {
        listOf(
                "vmess://not base64!",
                v2rayN(JSONObject().put("port", "443").put("id", uuid)),
                v2rayN(JSONObject().put("add", "h").put("port", "443")),
                v2rayN(JSONObject().put("add", "h").put("port", "x").put("id", uuid)),
                "vmess://$uuid@:443",
            )
            .forEach { link -> assertTrue(link, converter.convert(link, socksPort = 1).isFailure) }
    }
}
