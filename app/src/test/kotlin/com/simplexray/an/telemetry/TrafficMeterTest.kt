// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.telemetry

import com.simplexray.an.telemetry.TelemetryStore.Companion.MINUTE_MS
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficMeterTest {
    private val meter = TrafficMeter()
    private val run =
        TrafficMeter.RunInfo(
            "s1",
            "cfg",
            mapOf("proxy" to TrafficClass.PROXY, "direct" to TrafficClass.DIRECT),
        )

    private fun up(tag: String) = "outbound>>>$tag>>>traffic>>>uplink"

    private fun down(tag: String) = "outbound>>>$tag>>>traffic>>>downlink"

    @Test
    fun deltasAreSummedPerMinuteAndOutbound() {
        meter.record("r1", run, 10_000, mapOf(up("proxy") to 100, down("proxy") to 1000))
        meter.record("r1", run, 40_000, mapOf(up("proxy") to 150, down("proxy") to 3000))
        meter.record("r1", run, 50_000, mapOf(up("direct") to 7))

        val rows = meter.drain(now = MINUTE_MS + 1)

        assertEquals(2, rows.size)
        val proxy = rows.single { it.outbound == "proxy" }
        assertEquals(150L to 3000L, proxy.up to proxy.down)
        assertEquals(TrafficClass.PROXY, proxy.cls)
        assertEquals(TrafficClass.DIRECT, rows.single { it.outbound == "direct" }.cls)
    }

    @Test
    fun theCurrentMinuteStaysUntilItIsOver_orAllIsDrained() {
        meter.record("r1", run, MINUTE_MS + 5, mapOf(up("proxy") to 5))

        assertEquals(0, meter.drain(now = MINUTE_MS + 30_000).size)
        assertEquals(1, meter.drain(now = MINUTE_MS + 30_000, all = true).size)
    }

    @Test
    fun aCounterThatWentBackwardsCountsFromZero() {
        meter.record("r1", run, 0, mapOf(up("proxy") to 500))
        meter.record("r1", run, 1_000, mapOf(up("proxy") to 20))

        assertEquals(520L, meter.drain(now = MINUTE_MS).single().up)
    }

    @Test
    fun aNewRunStartsFromZero_andUnknownTagsAreOther() {
        meter.record("r1", run, 0, mapOf(up("proxy") to 500))
        meter.forget("r1")
        meter.record("r1", run, 1_000, mapOf(up("proxy") to 600, up("mystery") to 3))

        val rows = meter.drain(now = MINUTE_MS)
        assertEquals(1100L, rows.single { it.outbound == "proxy" }.up)
        assertEquals(TrafficClass.OTHER, rows.single { it.outbound == "mystery" }.cls)
    }

    @Test
    fun ignoresCountersThatAreNotOutboundTraffic() {
        meter.record(
            "r1",
            run,
            0,
            mapOf("inbound>>>socks>>>traffic>>>uplink" to 9, "user>>>a>>>traffic>>>uplink" to 9),
        )

        assertEquals(0, meter.drain(now = MINUTE_MS).size)
    }
}
