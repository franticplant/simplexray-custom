// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.telemetry

import com.simplexray.an.telemetry.TelemetryStore.Companion.DAY_MS
import com.simplexray.an.telemetry.TelemetryStore.Companion.MINUTE_MS
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderTest {
    private val store = TelemetryStore(Files.createTempDirectory("telemetry").toFile(), "svc")
    private var now = 20_746 * DAY_MS
    private var enabled = true
    private val recorder = Recorder(store, { now }, { enabled })
    private val classes = mapOf("proxy" to TrafficClass.PROXY, "direct" to TrafficClass.DIRECT)

    private fun sessions() = History.sessions(store.read(Kind.SESSIONS, 0, Long.MAX_VALUE))

    private fun events() = store.read(Kind.EVENTS, 0, Long.MAX_VALUE).map { it.getString("e") }

    @Test
    fun aSessionFromConnectToDisconnect() {
        val id = recorder.startSession(Mode.VPN, "Home")
        recorder.runStarted("/c/Home.json", "Home", true, "127.1.1.1:1000", classes)
        now += 800
        recorder.runReady("/c/Home.json")
        now += 10_000
        recorder.counters("127.1.1.1:1000", mapOf("outbound>>>proxy>>>traffic>>>downlink" to 4096))
        now += MINUTE_MS
        recorder.runEnded("/c/Home.json", "Home", true, 0, Outcome.STOPPED, null)
        recorder.endSession(EndReason.USER)

        val s = sessions().single()
        assertEquals(id, s.id)
        assertEquals(800L, s.timeToConnectMs)
        assertEquals(EndReason.USER, s.endReason)
        val run = History.runs(store, 0, Long.MAX_VALUE).single()
        assertEquals(800L, run.readyMs)
        assertEquals(id, run.sessionId)
        val traffic = History.traffic(store, 0, Long.MAX_VALUE).single()
        assertEquals(4096L, traffic.down)
        assertEquals(id, traffic.sessionId)
        assertEquals(
            listOf(
                "session.start",
                "instance.ready",
                "session.connected",
                "instance.stopped",
                "session.end",
            ),
            events(),
        )
    }

    @Test
    fun coresStoppedAfterTheDisconnect_stillBelongToTheSession() {
        val id = recorder.startSession(Mode.VPN, "A")
        recorder.runStarted("/c/A.json", "A", true, "k", classes)
        recorder.endSession(EndReason.USER)
        recorder.runEnded("/c/A.json", "A", true, 0, Outcome.STOPPED, null)

        assertEquals(id, History.runs(store, 0, Long.MAX_VALUE).single().sessionId)
        assertEquals(
            id,
            History.events(store, 0, Long.MAX_VALUE)
                .first { it.type == "instance.stopped" }
                .sessionId,
        )
    }

    @Test
    fun startSession_whileOneIsOpen_keepsIt() {
        val id = recorder.startSession(Mode.VPN, "A")

        assertEquals(id, recorder.startSession(Mode.VPN, "B"))
        assertEquals(1, sessions().size)
    }

    @Test
    fun onlyThePrimaryCoreConnectsTheSession() {
        recorder.startSession(Mode.VPN, "A")
        recorder.runStarted("/c/B.json", "B", false, "k2", classes)
        recorder.runReady("/c/B.json")

        assertNull(sessions().single().connectedAt)
    }

    @Test
    fun closeOrphans_endsAKilledSessionWhereItWasLastSeen() {
        recorder.startSession(Mode.VPN, "A")
        recorder.runStarted("/c/A.json", "A", true, "k", classes)
        recorder.runReady("/c/A.json")
        now += 5 * MINUTE_MS
        recorder.counters("k", mapOf("outbound>>>proxy>>>traffic>>>uplink" to 1))
        val lastSeen = now
        now += MINUTE_MS
        recorder.flushTraffic(all = true)
        // The process dies here; a new one starts later.
        now += DAY_MS
        val next = Recorder(store, { now })

        next.closeOrphans()
        next.closeOrphans()

        val s = sessions().single()
        assertEquals(EndReason.KILLED, s.endReason)
        assertEquals(lastSeen / MINUTE_MS * MINUTE_MS, s.endedAt)
        assertEquals(1, events().count { it == "session.killed" })
    }

    @Test
    fun runEnded_recordsTheOutcomeAndReason() {
        recorder.startSession(Mode.CORE_ONLY, "A")
        recorder.runStarted("/c/A.json", "A", true, "k", classes)
        now += 3_000
        recorder.runEnded("/c/A.json", "A", true, 23, Outcome.FAILED, "bad config")

        val run = History.runs(store, 0, Long.MAX_VALUE).single()
        assertEquals(Outcome.FAILED, run.outcome)
        assertEquals(23, run.exitCode)
        assertEquals("bad config", run.failure)
        assertNull(run.readyMs)
        assertTrue(events().contains("instance.failed"))
    }

    @Test
    fun nothingIsWrittenWhileDisabled() {
        enabled = false
        recorder.startSession(Mode.VPN, "A")
        recorder.endSession(EndReason.USER)

        assertEquals(0L, store.sizeBytes())
    }

    @Test
    fun outboundClasses_fromProtocols() {
        val config =
            JSONObject(
                """{"outbounds":[{"tag":"p","protocol":"vless"},{"tag":"d","protocol":"freedom"},
                {"tag":"b","protocol":"blackhole"},{"tag":"n","protocol":"dns"},{"protocol":"vmess"}]}"""
            )

        assertEquals(
            mapOf(
                "p" to TrafficClass.PROXY,
                "d" to TrafficClass.DIRECT,
                "b" to TrafficClass.BLOCK,
                "n" to TrafficClass.OTHER,
            ),
            Recorder.outboundClasses(config),
        )
    }
}
