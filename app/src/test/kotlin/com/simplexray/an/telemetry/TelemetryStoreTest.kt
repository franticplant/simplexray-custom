// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.telemetry

import com.simplexray.an.telemetry.TelemetryStore.Companion.DAY_MS
import com.simplexray.an.telemetry.TelemetryStore.Companion.HOUR_MS
import com.simplexray.an.telemetry.TelemetryStore.Companion.MINUTE_MS
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryStoreTest {
    private val root: File = Files.createTempDirectory("telemetry").toFile()
    private val svc = TelemetryStore(root, "svc")
    private val app = TelemetryStore(root, "app")

    // 2026-10-20T00:00:00Z
    private val day0 = 20_746 * DAY_MS

    private fun rec(t: Long) = JSONObject().put("t", t)

    @Test
    fun append_partitionsByDayAndProcess_andReadMergesThem() {
        svc.append(Kind.EVENTS, listOf(rec(day0 + 5), rec(day0 + DAY_MS + 5)))
        app.append(Kind.EVENTS, rec(day0 + 7))

        val names = File(root, "events").list()!!.sorted()
        assertEquals(
            listOf("2026-10-20.app.jsonl", "2026-10-20.svc.jsonl", "2026-10-21.svc.jsonl"),
            names,
        )
        assertEquals(3, svc.read(Kind.EVENTS, day0, day0 + 2 * DAY_MS).size)
        assertEquals(2, svc.read(Kind.EVENTS, day0, day0 + DAY_MS).size)
        assertEquals(1, svc.read(Kind.EVENTS, day0 + 6, day0 + 8).size)
    }

    @Test
    fun read_skipsALineCutShortByAKill() {
        svc.append(Kind.EVENTS, rec(day0 + 1))
        File(root, "events/2026-10-20.svc.jsonl").appendText("{\"t\":12")

        assertEquals(1, svc.read(Kind.EVENTS, day0, day0 + DAY_MS).size)
    }

    @Test
    fun expire_deletesWholeDaysOutsideTheRetention() {
        (0..9).forEach { svc.append(Kind.TRAFFIC, rec(day0 + it * DAY_MS)) }

        svc.expire(now = day0 + 9 * DAY_MS + HOUR_MS, keepDays = 3)

        val left = svc.read(Kind.TRAFFIC, 0, Long.MAX_VALUE).map { it.getLong("t") }
        assertEquals((7..9).map { day0 + it * DAY_MS }, left)
    }

    @Test
    fun compactTraffic_sumsOldMinutesIntoHours_once() {
        fun row(t: Long, up: Long) =
            Traffic(t, "s1", "cfg", "proxy", TrafficClass.PROXY, up, 2 * up).toJson()
        svc.append(
            Kind.TRAFFIC,
            listOf(row(day0, 1), row(day0 + MINUTE_MS, 2), row(day0 + HOUR_MS + 5 * MINUTE_MS, 4)),
        )
        val recent = day0 + 3 * DAY_MS
        svc.append(Kind.TRAFFIC, listOf(row(recent, 8), row(recent + MINUTE_MS, 8)))

        svc.compactTraffic(now = recent + HOUR_MS)
        svc.compactTraffic(now = recent + HOUR_MS)

        val old = svc.read(Kind.TRAFFIC, day0, day0 + DAY_MS).map(Traffic::fromJson)
        assertEquals(listOf(day0 to 3L, day0 + HOUR_MS to 4L), old.map { it.bucket to it.up })
        assertEquals(listOf(6L, 8L), old.map { it.down })
        assertEquals(2, svc.read(Kind.TRAFFIC, recent, recent + DAY_MS).size)
        assertTrue(File(root, "traffic/2026-10-20.svc.hourly.jsonl").isFile)
    }

    @Test
    fun compactTraffic_leavesTheOtherProcessesFilesAlone() {
        app.append(Kind.TRAFFIC, rec(day0))

        svc.compactTraffic(now = day0 + 10 * DAY_MS)

        assertTrue(File(root, "traffic/2026-10-20.app.jsonl").isFile)
    }

    @Test
    fun clear_removesEverything() {
        svc.append(Kind.EVENTS, rec(day0))
        assertTrue(svc.sizeBytes() > 0)

        svc.clear()

        assertEquals(0L, svc.sizeBytes())
        assertEquals(0, svc.read(Kind.EVENTS, 0, Long.MAX_VALUE).size)
    }
}
