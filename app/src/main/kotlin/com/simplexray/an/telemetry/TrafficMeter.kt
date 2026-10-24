// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.telemetry

import com.simplexray.an.telemetry.TelemetryStore.Companion.MINUTE_MS

/**
 * Turns readings of Xray's cumulative outbound counters into per-minute deltas.
 *
 * Counters are kept per run (a run's counters start at zero, and a restarted core is a new run), so
 * the first reading of a run counts in full. A counter lower than the last reading of the same run
 * also means it started again, and counts in full.
 */
class TrafficMeter {
    /** What a run's readings are attributed to. */
    data class RunInfo(
        val sessionId: String?,
        val config: String,
        val classes: Map<String, TrafficClass>,
    )

    private data class Key(
        val minute: Long,
        val sessionId: String?,
        val config: String,
        val outbound: String,
        val cls: TrafficClass,
    )

    private val last = HashMap<Pair<String, String>, Long>()
    private val pending = LinkedHashMap<Key, LongArray>()

    /**
     * Adds a reading of run [runKey]'s counters, named as Xray names them
     * ("outbound>>>tag>>>traffic>>>uplink").
     */
    @Synchronized
    fun record(runKey: String, run: RunInfo, time: Long, counters: Map<String, Long>) {
        val minute = time / MINUTE_MS * MINUTE_MS
        counters.forEach { (name, value) ->
            val parts = name.split(">>>")
            if (parts.size != 4 || parts[0] != "outbound" || parts[2] != "traffic") return@forEach
            val direction =
                when (parts[3]) {
                    "uplink" -> 0
                    "downlink" -> 1
                    else -> return@forEach
                }
            val previous = last.put(runKey to name, value)
            val delta = if (previous == null || value < previous) value else value - previous
            if (delta <= 0) return@forEach
            val tag = parts[1]
            val key =
                Key(minute, run.sessionId, run.config, tag, run.classes[tag] ?: TrafficClass.OTHER)
            pending.getOrPut(key) { LongArray(2) }[direction] += delta
        }
    }

    /** Forgets a run's last readings, once it has ended. */
    @Synchronized
    fun forget(runKey: String) {
        last.keys.removeAll { it.first == runKey }
    }

    /** Takes the minutes before [now]'s (complete ones), or all of them if [all]. */
    @Synchronized
    fun drain(now: Long, all: Boolean = false): List<Traffic> {
        val current = now / MINUTE_MS * MINUTE_MS
        val done = pending.keys.filter { all || it.minute < current }
        return done.map { key ->
            val (up, down) = pending.remove(key)!!.let { it[0] to it[1] }
            Traffic(key.minute, key.sessionId, key.config, key.outbound, key.cls, up, down)
        }
    }
}
