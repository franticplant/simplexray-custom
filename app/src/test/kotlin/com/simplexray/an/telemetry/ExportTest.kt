// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.telemetry

import com.simplexray.an.telemetry.TelemetryStore.Companion.DAY_MS
import com.simplexray.an.telemetry.TelemetryStore.Companion.MINUTE_MS
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTest {
    private val dir: File = Files.createTempDirectory("telemetry").toFile()
    private val store = TelemetryStore(File(dir, "t"), "svc")
    private var now = 20_746 * DAY_MS
    private val recorder = Recorder(store, { now })

    private fun export(redact: Boolean, logs: Map<String, File> = emptyMap()): Map<String, String> {
        val out = ByteArrayOutputStream()
        Export.write(
            store,
            Export.Options(
                now - DAY_MS,
                now + 1,
                now,
                ZoneOffset.UTC,
                redact,
                mapOf("app_version" to "1.2.3"),
                logs,
            ),
            out,
        )
        val files = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                files[e.name] = zip.readBytes().decodeToString()
            }
        }
        return files
    }

    private fun recordASession() {
        recorder.startSession(Mode.VPN, "de1.example.net")
        recorder.runStarted(
            "/c/x.json",
            "de1.example.net",
            true,
            "k",
            mapOf("proxy" to TrafficClass.PROXY),
        )
        now += 500
        recorder.runReady("/c/x.json")
        recorder.counters("k", mapOf("outbound>>>proxy>>>traffic>>>downlink" to 2048))
        now += MINUTE_MS
        recorder.runEnded(
            "/c/x.json",
            "de1.example.net",
            true,
            1,
            Outcome.CRASHED,
            "dial tcp 203.0.113.9:443: refused",
        )
        recorder.endSession(EndReason.REVOKED, "taken by another VPN")
    }

    @Test
    fun theZipHasEveryPart_withTheRecordedFigures() {
        recordASession()

        val files = export(redact = false)

        assertEquals(
            setOf(
                "manifest.json",
                "summary.txt",
                "sessions.csv",
                "runs.csv",
                "traffic_hourly.csv",
                "events.jsonl",
            ),
            files.keys,
        )
        val manifest = JSONObject(files["manifest.json"]!!)
        assertEquals("1.2.3", manifest.getString("app_version"))
        assertFalse(manifest.getBoolean("redacted"))
        val session = files["sessions.csv"]!!.lines()[1].split(",")
        assertEquals("de1.example.net", session[1])
        assertEquals("500", session[4])
        assertEquals("revoked", session[7])
        assertEquals("2048", session[10])
        assertTrue(files["runs.csv"]!!.contains("crashed,dial tcp 203.0.113.9:443: refused"))
        assertTrue(files["traffic_hourly.csv"]!!.contains(",proxy,proxy,0,2048"))
        assertTrue(files["summary.txt"]!!.contains("sessions that connected: 1 of 1 (100%)"))
        files["events.jsonl"]!!.lines().filter { it.isNotEmpty() }.forEach { JSONObject(it) }
    }

    @Test
    fun redaction_coversNamesFailuresEventsAndLogs() {
        recordASession()
        val log =
            File(dir, "log.txt").apply {
                writeText("accepted tcp:www.google.com:443 via 198.51.100.4\n")
            }

        val files = export(redact = true, logs = mapOf("session.txt" to log))

        val all = files.values.joinToString("\n")
        listOf("example.net", "203.0.113.9", "google", "198.51.100.4").forEach {
            assertFalse("$it leaked", all.contains(it))
        }
        assertTrue(files["logs/session.txt"]!!.contains("<domain>.com"))
        assertTrue(JSONObject(files["manifest.json"]!!).getBoolean("redacted"))
    }

    @Test
    fun csv_quotesWhatNeedsIt() {
        assertEquals(
            "a,\"b,c\",\"say \"\"hi\"\"\"\r\n",
            Export.csv(listOf("a", "b,c", "say \"hi\""), emptyList()),
        )
    }
}
