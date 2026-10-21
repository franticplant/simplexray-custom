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
}
