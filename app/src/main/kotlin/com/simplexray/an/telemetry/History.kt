// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.telemetry

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.json.JSONObject

/** Bytes by traffic class. */
data class ClassTotals(
    val proxy: Long = 0,
    val direct: Long = 0,
    val block: Long = 0,
    val other: Long = 0,
) {
    val total: Long
        get() = proxy + direct + block + other

    operator fun plus(t: Traffic): ClassTotals =
        when (t.cls) {
            TrafficClass.PROXY -> copy(proxy = proxy + t.total)
            TrafficClass.DIRECT -> copy(direct = direct + t.total)
            TrafficClass.BLOCK -> copy(block = block + t.total)
            TrafficClass.OTHER -> copy(other = other + t.total)
        }
}

data class Bucket(val start: Long, val bytes: ClassTotals)

data class ConfigUsage(val config: String, val bytes: ClassTotals, val connectedMs: Long)

data class Reliability(
    val sessions: Int,
    val connected: Int,
    val medianTimeToConnectMs: Long?,
    val p90TimeToConnectMs: Long?,
    val crashes: Int,
    val failedRuns: Int,
    val endReasons: Map<EndReason, Int>,
    /** The most common failure reasons, most common first. */
    val topFailures: List<Pair<String, Int>>,
) {
    /** Sessions that got connected, of those that ended or did; null with none. */
    val successRate: Double?
        get() = if (sessions == 0) null else connected.toDouble() / sessions
}

data class Overview(
    val from: Long,
    val to: Long,
    val bytes: ClassTotals,
    val up: Long,
    val down: Long,
    val series: List<Bucket>,
    val bucketMs: Long,
    val byConfig: List<ConfigUsage>,
    val connectedMs: Long,
    val reliability: Reliability,
)

data class SessionDetail(
    val session: Session,
    val bytes: ClassTotals,
    val up: Long,
    val down: Long,
    val series: List<Bucket>,
    val runs: List<Run>,
    val events: List<Event>,
)

/** Reads the store back into what the screens and the export show. Pure apart from the store. */
object History {
    /** Folds session lines (start, connected, end) into sessions, newest first. */
    fun sessions(lines: List<JSONObject>): List<Session> {
        val byId = LinkedHashMap<String, Session>()
        lines
            .sortedBy { it.optLong("t") }
            .forEach { o ->
                val id = o.optString("id")
                val t = o.optLong("t")
                when (o.optString("type")) {
                    "start" ->
                        byId[id] =
                            Session(
                                id,
                                Mode.of(o.optStringOrNull("mode")),
                                o.optString("config"),
                                t,
                            )
                    "connected" -> byId[id]?.let { byId[id] = it.copy(connectedAt = t) }
                    "end" ->
                        byId[id]?.let {
                            byId[id] =
                                it.copy(
                                    endedAt = t,
                                    endReason = EndReason.of(o.optStringOrNull("reason")),
                                    endDetail = o.optStringOrNull("detail"),
                                )
                        }
                }
            }
        return byId.values.sortedByDescending { it.startedAt }
    }

    /** Sessions overlapping [from, to), newest first. */
    fun sessions(store: TelemetryStore, from: Long, to: Long): List<Session> =
        // A session can start before the range, so look a little further back.
        sessions(store.read(Kind.SESSIONS, from - SESSION_LOOKBACK_MS, to)).filter {
            it.startedAt < to && (it.endedAt ?: Long.MAX_VALUE) > from
        }

    fun traffic(store: TelemetryStore, from: Long, to: Long): List<Traffic> =
        store.read(Kind.TRAFFIC, from, to).map(Traffic::fromJson)

    fun runs(store: TelemetryStore, from: Long, to: Long): List<Run> =
        store.read(Kind.RUNS, from, to).map(Run::fromJson)

    fun events(store: TelemetryStore, from: Long, to: Long): List<Event> =
        store.read(Kind.EVENTS, from, to).map(Event::fromJson).sortedByDescending { it.time }

    fun overview(
        store: TelemetryStore,
        from: Long,
        to: Long,
        now: Long,
        bucketMs: Long,
        zone: ZoneId,
    ): Overview {
        val traffic = traffic(store, from, to)
        val sessions = sessions(store, from, to)
        val runs = runs(store, from, to)
        return overview(from, to, now, bucketMs, zone, traffic, sessions, runs)
    }

    fun overview(
        from: Long,
        to: Long,
        now: Long,
        bucketMs: Long,
        zone: ZoneId,
        traffic: List<Traffic>,
        sessions: List<Session>,
        runs: List<Run>,
    ): Overview {
        val connectedBy = HashMap<String, Long>()
        sessions.forEach { s ->
            connectedBy.merge(s.config, clippedMs(s, from, to, now), Long::plus)
        }
        val bytesBy = traffic.groupBy { it.config }
        val configs = (bytesBy.keys + connectedBy.keys).distinct()
        val byConfig =
            configs
                .map { c ->
                    ConfigUsage(
                        c,
                        bytesBy[c].orEmpty().fold(ClassTotals()) { a, t -> a + t },
                        connectedBy[c] ?: 0,
                    )
                }
                .sortedWith(
                    compareByDescending<ConfigUsage> { it.bytes.total }
                        .thenByDescending { it.connectedMs }
                )
        return Overview(
            from = from,
            to = to,
            bytes = traffic.fold(ClassTotals()) { a, t -> a + t },
            up = traffic.sumOf { it.up },
            down = traffic.sumOf { it.down },
            series = series(traffic, from, to, bucketMs, zone),
            bucketMs = bucketMs,
            byConfig = byConfig,
            connectedMs = sessions.sumOf { clippedMs(it, from, to, now) },
            reliability = reliability(sessions.filter { it.startedAt >= from }, runs),
        )
    }

    fun sessionDetail(
        store: TelemetryStore,
        session: Session,
        now: Long,
        zone: ZoneId,
    ): SessionDetail {
        val from = session.startedAt
        val to = (session.endedAt ?: now) + 1
        val traffic =
            traffic(store, from - TelemetryStore.MINUTE_MS, to).filter {
                it.sessionId == session.id
            }
        val span = to - from
        val bucket =
            when {
                span <= 2 * TelemetryStore.HOUR_MS -> TelemetryStore.MINUTE_MS
                span <= 2 * TelemetryStore.DAY_MS -> 15 * TelemetryStore.MINUTE_MS
                else -> TelemetryStore.HOUR_MS
            }
        return SessionDetail(
            session = session,
            bytes = traffic.fold(ClassTotals()) { a, t -> a + t },
            up = traffic.sumOf { it.up },
            down = traffic.sumOf { it.down },
            series = series(traffic, from, to, bucket, zone),
            runs =
                runs(store, from, to)
                    .filter { it.sessionId == session.id }
                    .sortedBy { it.startedAt },
            events = events(store, from, to).filter { it.sessionId == session.id },
        )
    }

    /**
     * Traffic summed into buckets of [bucketMs] covering
     * [from, to), every bucket present. Buckets of an hour or more start on local hour or day boundaries in [zone].
     */
    fun series(
        traffic: List<Traffic>,
        from: Long,
        to: Long,
        bucketMs: Long,
        zone: ZoneId,
    ): List<Bucket> {
        val starts = mutableListOf<Long>()
        var t = align(from, bucketMs, zone)
        while (t < to) {
            starts += t
            t = next(t, bucketMs, zone)
        }
        if (starts.isEmpty()) return emptyList()
        val totals = Array(starts.size) { ClassTotals() }
        traffic.forEach { row ->
            val i = starts.binarySearch(row.bucket).let { if (it >= 0) it else -it - 2 }
            if (i in totals.indices) totals[i] = totals[i] + row
        }
        return starts.mapIndexed { i, start -> Bucket(start, totals[i]) }
    }

    fun reliability(sessions: List<Session>, runs: List<Run>): Reliability {
        val ttc = sessions.mapNotNull { it.timeToConnectMs }.sorted()
        // A session still connecting is not a failure yet.
        val decided = sessions.filter { it.connectedAt != null || !it.isOpen }
        val failures =
            runs
                .filter { it.outcome != Outcome.STOPPED && it.failure != null }
                .groupingBy { normalizeFailure(it.failure!!) }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(5)
                .map { it.key to it.value }
        return Reliability(
            sessions = decided.size,
            connected = decided.count { it.connectedAt != null },
            medianTimeToConnectMs = percentile(ttc, 0.5),
            p90TimeToConnectMs = percentile(ttc, 0.9),
            crashes = runs.count { it.outcome == Outcome.CRASHED },
            failedRuns =
                runs.count { it.outcome == Outcome.FAILED || it.outcome == Outcome.TIMEOUT },
            endReasons = sessions.mapNotNull { it.endReason }.groupingBy { it }.eachCount(),
            topFailures = failures,
        )
    }

    /**
     * A failure reason without what differs between occurrences of the same problem: Xray's
     * timestamps, addresses and numbers.
     */
    fun normalizeFailure(reason: String): String =
        reason
            .replace(Regex("""\d{4}/\d\d/\d\d \d\d:\d\d:\d\d(\.\d+)?\s*"""), "")
            .replace(Regex("""\[[0-9a-fA-F:]+]|\d{1,3}(\.\d{1,3}){3}"""), "<addr>")
            .replace(Regex("""\d+"""), "#")
            .trim()

    internal fun percentile(sorted: List<Long>, p: Double): Long? =
        if (sorted.isEmpty()) null else sorted[((sorted.size - 1) * p).toInt()]

    private fun clippedMs(s: Session, from: Long, to: Long, now: Long): Long {
        val start = maxOf(s.connectedAt ?: return 0, from)
        val end = minOf(s.endedAt ?: now, to)
        return (end - start).coerceAtLeast(0)
    }

    private fun align(t: Long, bucketMs: Long, zone: ZoneId): Long =
        when {
            bucketMs >= TelemetryStore.DAY_MS ->
                Instant.ofEpochMilli(t)
                    .atZone(zone)
                    .truncatedTo(ChronoUnit.DAYS)
                    .toInstant()
                    .toEpochMilli()
            bucketMs >= TelemetryStore.HOUR_MS ->
                Instant.ofEpochMilli(t)
                    .atZone(zone)
                    .truncatedTo(ChronoUnit.HOURS)
                    .toInstant()
                    .toEpochMilli()
            else -> t / bucketMs * bucketMs
        }

    private fun next(t: Long, bucketMs: Long, zone: ZoneId): Long =
        if (bucketMs >= TelemetryStore.DAY_MS) {
            // Calendar days, which are not always 24 hours long.
            Instant.ofEpochMilli(t)
                .atZone(zone)
                .plusDays(bucketMs / TelemetryStore.DAY_MS)
                .toInstant()
                .toEpochMilli()
        } else {
            t + bucketMs
        }

    private const val SESSION_LOOKBACK_MS = 7 * TelemetryStore.DAY_MS
}
