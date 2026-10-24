// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.telemetry

import com.simplexray.an.telemetry.TelemetryStore.Companion.DAY_MS
import org.json.JSONArray
import org.json.JSONObject

/**
 * The service's side of telemetry: opens and closes sessions, records each Xray run, turns counter
 * readings into traffic records and writes events. Every method is safe to call from any thread.
 *
 * [enabled] is read on every write, so turning history off takes effect at once.
 */
class Recorder(
    private val store: TelemetryStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val enabled: () -> Boolean = { true },
) {
    private class RunState(
        val config: String,
        val primary: Boolean,
        val startedAt: Long,
        val key: String,
        val info: TrafficMeter.RunInfo,
        var readyMs: Long? = null,
    )

    private val meter = TrafficMeter()
    private val runs = HashMap<String, RunState>() // by config path; guarded by this
    private var session: Session? = null // guarded by this

    val currentSessionId: String?
        @Synchronized get() = session?.id

    /**
     * Closes sessions a killed process left open, as [EndReason.KILLED] at the last moment they
     * were seen alive. Call once when the service starts, before opening a session.
     */
    @Synchronized
    fun closeOrphans() {
        val now = clock()
        val from = now - ORPHAN_LOOKBACK_MS
        val current = session?.id
        val open =
            History.sessions(store.read(Kind.SESSIONS, from, now + 1)).filter {
                it.isOpen && it.id != current
            }
        if (open.isEmpty()) return
        val lastSeen = HashMap<String, Long>()
        listOf(Kind.TRAFFIC, Kind.RUNS, Kind.EVENTS).forEach { kind ->
            store.read(kind, from, now + 1).forEach { o ->
                val id = o.optStringOrNull("s") ?: return@forEach
                val t = maxOf(o.optLong("t"), o.optLong("end"))
                lastSeen.merge(id, t, ::maxOf)
            }
        }
        open.forEach { s ->
            val end = maxOf(lastSeen[s.id] ?: s.startedAt, s.connectedAt ?: s.startedAt)
            write(Kind.SESSIONS, sessionLine("end", s.id, end).put("reason", EndReason.KILLED.key))
            write(
                Kind.EVENTS,
                Event(
                        end,
                        Level.WARN,
                        Category.SESSION,
                        "session.killed",
                        "Session ended unexpectedly: the app was stopped by the system",
                        s.id,
                    )
                    .toJson(),
            )
        }
    }

    /** Opens a session, unless one is open already; returns the open session's id. */
    @Synchronized
    fun startSession(mode: Mode, config: String): String {
        session?.let {
            return it.id
        }
        val now = clock()
        val id = newId(now)
        session = Session(id, mode, config, now)
        write(
            Kind.SESSIONS,
            sessionLine("start", id, now).put("mode", mode.key).put("config", config),
        )
        event(
            Level.INFO,
            Category.SESSION,
            "session.start",
            "Connecting with $config" + if (mode == Mode.CORE_ONLY) " (core only)" else "",
        )
        return id
    }

    @Synchronized
    fun endSession(reason: EndReason, detail: String? = null) {
        val s = session ?: return
        val now = clock()
        flushTraffic(all = true)
        write(
            Kind.SESSIONS,
            sessionLine("end", s.id, now).put("reason", reason.key).putOpt("detail", detail),
        )
        val level = if (reason == EndReason.USER) Level.INFO else Level.WARN
        event(
            level,
            Category.SESSION,
            "session.end",
            "Disconnected (${reason.key})" + detail?.let { ": $it" }.orEmpty(),
            mapOf("reason" to reason.key, "duration_s" to ((now - s.startedAt) / 1000).toString()),
        )
        session = null
    }

    @Synchronized
    fun runStarted(
        configPath: String,
        config: String,
        primary: Boolean,
        key: String,
        classes: Map<String, TrafficClass>,
    ) {
        runs[configPath] =
            RunState(
                config,
                primary,
                clock(),
                key,
                TrafficMeter.RunInfo(session?.id, config, classes),
            )
    }

    @Synchronized
    fun runReady(configPath: String) {
        val run = runs[configPath] ?: return
        val now = clock()
        run.readyMs = now - run.startedAt
        event(
            Level.INFO,
            Category.INSTANCE,
            "instance.ready",
            "${run.config} is running (ready in ${run.readyMs} ms)",
            mapOf("config" to run.config, "ready_ms" to run.readyMs.toString()),
        )
        val s = session
        if (run.primary && s != null && s.connectedAt == null) {
            session = s.copy(connectedAt = now)
            write(Kind.SESSIONS, sessionLine("connected", s.id, now))
            event(
                Level.INFO,
                Category.SESSION,
                "session.connected",
                "Connected in ${now - s.startedAt} ms",
                mapOf("time_to_connect_ms" to (now - s.startedAt).toString()),
            )
        }
    }

    @Synchronized
    fun runEnded(
        configPath: String,
        config: String,
        primary: Boolean,
        exitCode: Int?,
        outcome: Outcome,
        failure: String?,
    ) {
        val now = clock()
        val run = runs.remove(configPath)
        run?.let { meter.forget(it.key) }
        // The session the run belonged to: a disconnect closes the session before its cores stop.
        val sessionId = run?.info?.sessionId ?: session?.id
        write(
            Kind.RUNS,
            Run(
                    sessionId = sessionId,
                    config = config,
                    primary = primary,
                    startedAt = run?.startedAt ?: now,
                    readyMs = run?.readyMs,
                    endedAt = now,
                    exitCode = exitCode,
                    outcome = outcome,
                    failure = failure,
                )
                .toJson(),
        )
        val (level, type, text) =
            when (outcome) {
                Outcome.STOPPED -> Triple(Level.INFO, "instance.stopped", "$config stopped")
                Outcome.CRASHED ->
                    Triple(Level.WARN, "instance.crashed", "$config crashed; restarting")
                Outcome.TIMEOUT ->
                    Triple(Level.ERROR, "instance.timeout", "$config did not become ready")
                Outcome.FAILED -> Triple(Level.ERROR, "instance.failed", "$config failed")
            }
        event(
            level,
            Category.INSTANCE,
            type,
            text + failure?.let { ": $it" }.orEmpty(),
            buildMap {
                put("config", config)
                exitCode?.let { put("exit_code", it.toString()) }
            },
            sessionId,
        )
    }

    /** The keys of the runs whose counters can be read now. */
    @Synchronized
    fun readyRuns(): List<String> = runs.values.filter { it.readyMs != null }.map { it.key }

    /** Adds a reading of a run's counters; see [TrafficMeter.record]. */
    @Synchronized
    fun counters(runKey: String, counters: Map<String, Long>) {
        val run = runs.values.firstOrNull { it.key == runKey } ?: return
        meter.record(runKey, run.info, clock(), counters)
        flushTraffic(all = false)
    }

    @Synchronized
    fun flushTraffic(all: Boolean) {
        val rows = meter.drain(clock(), all)
        if (rows.isNotEmpty() && enabled()) store.append(Kind.TRAFFIC, rows.map { it.toJson() })
    }

    @Synchronized
    fun event(
        level: Level,
        category: Category,
        type: String,
        message: String,
        attrs: Map<String, String> = emptyMap(),
        sessionId: String? = session?.id,
    ) {
        write(
            Kind.EVENTS,
            Event(clock(), level, category, type, message, sessionId, attrs).toJson(),
        )
    }

    /** Expiry and compaction; the service runs it on start and then hourly. */
    fun maintain(keepDays: Int) {
        val now = clock()
        store.expire(now, keepDays)
        store.compactTraffic(now)
    }

    private fun write(kind: Kind, record: JSONObject) {
        if (enabled()) store.append(kind, record)
    }

    private fun sessionLine(type: String, id: String, time: Long) =
        JSONObject().put("type", type).put("id", id).put("t", time)

    companion object {
        // A session open for longer than this is not looked for when closing orphans.
        private const val ORPHAN_LOOKBACK_MS = 7 * DAY_MS

        private fun newId(now: Long) =
            now.toString(36) +
                "-" +
                (0 until 4).map { "0123456789abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")

        /**
         * Each outbound tag's traffic class, from the config's outbound protocols. Pass the config
         * as launched, after ConfigUtils.tagOutbounds.
         */
        fun outboundClasses(config: JSONObject): Map<String, TrafficClass> {
            val outbounds = config.optJSONArray("outbounds") ?: JSONArray()
            return (0 until outbounds.length())
                .mapNotNull { outbounds.optJSONObject(it) }
                .filter { it.has("tag") }
                .associate {
                    it.optString("tag") to TrafficClass.ofProtocol(it.optStringOrNull("protocol"))
                }
        }
    }
}
