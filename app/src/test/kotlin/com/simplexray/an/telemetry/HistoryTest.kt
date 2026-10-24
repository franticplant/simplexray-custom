// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.telemetry

import com.simplexray.an.telemetry.TelemetryStore.Companion.DAY_MS
import com.simplexray.an.telemetry.TelemetryStore.Companion.HOUR_MS
import com.simplexray.an.telemetry.TelemetryStore.Companion.MINUTE_MS
import java.time.ZoneId
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val day0 = 20_746 * DAY_MS

    private fun traffic(t: Long, config: String, cls: TrafficClass, bytes: Long) =
        Traffic(t, "s", config, cls.key, cls, bytes, 0)

    private fun session(
        id: String,
        start: Long,
        connectAfter: Long?,
        end: Long?,
        reason: EndReason? = EndReason.USER,
        config: String = "A",
    ) =
        Session(
            id,
            Mode.VPN,
            config,
            start,
            connectAfter?.let { start + it },
            end,
            if (end != null) reason else null,
        )

    @Test
    fun sessions_foldStartConnectedAndEnd() {
        val lines =
            listOf(
                JSONObject()
                    .put("type", "end")
                    .put("id", "a")
                    .put("t", 30)
                    .put("reason", "revoked"),
                JSONObject()
                    .put("type", "start")
                    .put("id", "a")
                    .put("t", 10)
                    .put("mode", "core")
                    .put("config", "X"),
                JSONObject().put("type", "connected").put("id", "a").put("t", 12),
                JSONObject().put("type", "start").put("id", "b").put("t", 40).put("config", "Y"),
            )

        val (b, a) = History.sessions(lines)

        assertEquals(Session("a", Mode.CORE_ONLY, "X", 10, 12, 30, EndReason.REVOKED), a)
        assertEquals("b", b.id)
        assertEquals(true, b.isOpen)
    }

    @Test
    fun series_fillsEveryBucketAndSumsByClass() {
        val rows =
            listOf(
                traffic(day0 + 5 * MINUTE_MS, "A", TrafficClass.PROXY, 10),
                traffic(day0 + 59 * MINUTE_MS, "A", TrafficClass.DIRECT, 1),
                traffic(day0 + 2 * HOUR_MS, "A", TrafficClass.PROXY, 7),
            )

        val series = History.series(rows, day0, day0 + 3 * HOUR_MS, HOUR_MS, utc)

        assertEquals(listOf(day0, day0 + HOUR_MS, day0 + 2 * HOUR_MS), series.map { it.start })
        assertEquals(listOf(10L, 0L, 7L), series.map { it.bytes.proxy })
        assertEquals(1L, series[0].bytes.direct)
    }

    @Test
    fun series_daysFollowTheLocalCalendar() {
        val zone = ZoneId.of("Asia/Jakarta") // UTC+7
        val rows = listOf(traffic(day0 + 18 * HOUR_MS, "A", TrafficClass.PROXY, 5))

        val series = History.series(rows, day0, day0 + 2 * DAY_MS, DAY_MS, zone)

        // 18:00 UTC on day 0 is 01:00 on day 1 in Jakarta.
        assertEquals(day0 + 17 * HOUR_MS, series[1].start)
        assertEquals(5L, series[1].bytes.proxy)
    }

    @Test
    fun overview_perConfigUsageAndConnectedTime() {
        val now = day0 + 10 * HOUR_MS
        val sessions =
            listOf(
                session("1", day0 + HOUR_MS, 1_000, day0 + 3 * HOUR_MS),
                session("2", day0 + 5 * HOUR_MS, 2_000, null, config = "B"),
            )
        val rows =
            listOf(
                traffic(day0 + 2 * HOUR_MS, "A", TrafficClass.PROXY, 100),
                traffic(day0 + 6 * HOUR_MS, "B", TrafficClass.PROXY, 500),
                traffic(day0 + 6 * HOUR_MS, "B", TrafficClass.BLOCK, 5),
            )

        val o = History.overview(day0, now, now, HOUR_MS, utc, rows, sessions, emptyList())

        assertEquals(listOf("B", "A"), o.byConfig.map { it.config })
        assertEquals(505L, o.byConfig[0].bytes.total)
        assertEquals(5 * HOUR_MS - 2_000, o.byConfig[0].connectedMs)
        assertEquals(2 * HOUR_MS - 1_000, o.byConfig[1].connectedMs)
        assertEquals(ClassTotals(proxy = 600, block = 5), o.bytes)
    }

    @Test
    fun reliability_ratesTimesAndFailures() {
        val sessions =
            listOf(
                session("1", 0, 1_000, 10),
                session("2", 0, 3_000, 10, EndReason.REVOKED),
                session("3", 0, null, 10, EndReason.FAILED),
                session("4", 0, null, null), // still connecting: not counted yet
            )
        fun run(outcome: Outcome, why: String?) = Run("s", "A", true, 0, null, 1, 1, outcome, why)
        val runs =
            listOf(
                run(
                    Outcome.CRASHED,
                    "Xray exited with code 2: 2026/10/24 16:39:08.1 panic at 10.0.0.1",
                ),
                run(
                    Outcome.CRASHED,
                    "Xray exited with code 2: 2026/10/25 01:02:03 panic at 10.0.0.9",
                ),
                run(Outcome.FAILED, "port in use"),
                run(Outcome.STOPPED, null),
            )

        val r = History.reliability(sessions, runs)

        assertEquals(3, r.sessions)
        assertEquals(2, r.connected)
        assertEquals(2.0 / 3, r.successRate!!, 1e-9)
        assertEquals(1_000L, r.medianTimeToConnectMs)
        assertEquals(2, r.crashes)
        assertEquals(1, r.failedRuns)
        assertEquals("Xray exited with code #: panic at <addr>" to 2, r.topFailures.first())
        assertEquals(
            mapOf(EndReason.USER to 1, EndReason.REVOKED to 1, EndReason.FAILED to 1),
            r.endReasons,
        )
    }

    @Test
    fun reliability_aSessionWhoseCoreFailedHasFailed_evenWhileOpen() {
        val sessions = listOf(session("1", 0, null, null), session("2", 0, 500, 10))
        val runs = listOf(Run("1", "A", true, 0, null, 1, 23, Outcome.FAILED, "bad config"))

        val r = History.reliability(sessions, runs)

        assertEquals(2, r.sessions)
        assertEquals(1, r.connected)
    }

    @Test
    fun reliability_withNoSessionsHasNoRate() {
        val r = History.reliability(emptyList(), emptyList())

        assertNull(r.successRate)
        assertNull(r.medianTimeToConnectMs)
    }
}
