// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.common

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigUtilsTest {
    @Test
    fun formatConfigContent_dropsLogFilePaths() {
        val config =
            """{"log":{"access":"/sdcard/a.log","error":"/sdcard/e.log","loglevel":"warning"}}"""

        val log = JSONObject(ConfigUtils.formatConfigContent(config)).getJSONObject("log")

        assertFalse(log.has("access"))
        assertFalse(log.has("error"))
        assertEquals("warning", log.getString("loglevel"))
    }

    @Test
    fun formatConfigContent_keepsDisabledLogs() {
        val config = """{"log":{"access":"none","error":"none"}}"""

        val log = JSONObject(ConfigUtils.formatConfigContent(config)).getJSONObject("log")

        assertEquals("none", log.getString("access"))
        assertEquals("none", log.getString("error"))
    }

    @Test
    fun formatConfigContent_acceptsConfigWithoutLogSection() {
        val formatted = ConfigUtils.formatConfigContent("""{"inbounds":[]}""")

        assertTrue(JSONObject(formatted).has("inbounds"))
    }

    @Test
    fun formatConfigContent_doesNotEscapeSlashes() {
        val formatted = ConfigUtils.formatConfigContent("""{"path":"/ws/path"}""")

        assertTrue(formatted.contains("\"/ws/path\""))
    }

    @Test
    fun injectStatsService_addsApiStatsAndPolicy() {
        val config = JSONObject(ConfigUtils.injectStatsService("127.1.2.3", 14222, "{}"))

        val api = config.getJSONObject("api")
        assertEquals("127.1.2.3:14222", api.getString("listen"))
        assertEquals("StatsService", api.getJSONArray("services").getString(0))
        assertTrue(config.has("stats"))
        val system = config.getJSONObject("policy").getJSONObject("system")
        assertTrue(system.getBoolean("statsOutboundUplink"))
        assertTrue(system.getBoolean("statsOutboundDownlink"))
    }

    @Test
    fun extractPortsFromJson_findsPortsInNestedObjectsAndArrays() {
        val config =
            """
            {
              "inbounds": [
                {"port": 10808, "protocol": "socks"},
                {"port": 10809, "protocol": "http"}
              ],
              "outbounds": [
                {"settings": {"vnext": [{"address": "example.com", "port": 443}]}}
              ]
            }
            """

        assertEquals(setOf(10808, 10809, 443), ConfigUtils.extractPortsFromJson(config))
    }

    @Test
    fun extractPortsFromJson_ignoresOutOfRangeValues() {
        assertEquals(emptySet<Int>(), ConfigUtils.extractPortsFromJson("""{"port": 70000}"""))
    }

    @Test
    fun extractPortsFromJson_returnsEmptyOnInvalidJson() {
        assertEquals(emptySet<Int>(), ConfigUtils.extractPortsFromJson("not json"))
    }

    @Test
    fun injectStatsService_keepsTheConfigsOwnSettings() {
        val original =
            """{"api":{"tag":"ctl","services":["HandlerService"]},"stats":{},
               "policy":{"levels":{"0":{"handshake":2}},"system":{"statsInboundUplink":true}}}"""

        val config = JSONObject(ConfigUtils.injectStatsService("127.0.0.9", 20000, original))

        val api = config.getJSONObject("api")
        assertEquals("ctl", api.getString("tag"))
        assertEquals("127.0.0.9:20000", api.getString("listen"))
        val services = api.getJSONArray("services")
        assertEquals(
            listOf("HandlerService", "StatsService"),
            (0 until services.length()).map(services::getString),
        )
        val policy = config.getJSONObject("policy")
        assertEquals(2, policy.getJSONObject("levels").getJSONObject("0").getInt("handshake"))
        val system = policy.getJSONObject("system")
        assertTrue(system.getBoolean("statsInboundUplink"))
        assertTrue(system.getBoolean("statsOutboundUplink"))
    }

    @Test
    fun injectStatsService_doesNotDuplicateStatsService() {
        val config =
            JSONObject(
                ConfigUtils.injectStatsService(
                    "127.0.0.9",
                    20000,
                    """{"api":{"services":["StatsService"]}}""",
                )
            )

        assertEquals(1, config.getJSONObject("api").getJSONArray("services").length())
    }
}
