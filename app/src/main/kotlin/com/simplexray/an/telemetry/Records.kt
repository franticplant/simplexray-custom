// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.telemetry

import org.json.JSONObject

/** The kinds of record the store keeps, each in its own directory. */
enum class Kind(val dir: String) {
    SESSIONS("sessions"),
    RUNS("runs"),
    TRAFFIC("traffic"),
    EVENTS("events"),
}

enum class Level {
    INFO,
    WARN,
    ERROR,
}

enum class Category(val key: String) {
    SESSION("session"),
    INSTANCE("instance"),
    NETWORK("network"),
    CONNECTIVITY("connectivity"),
    CONFIG("config"),
    PERMISSION("permission"),
    HISTORY("history"),
}

enum class Mode(val key: String) {
    VPN("vpn"),
    CORE_ONLY("core");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: VPN
    }
}

/** How a session ended. */
enum class EndReason(val key: String) {
    USER("user"),
    REVOKED("revoked"),
    FAILED("failed"),
    STOPPED("stopped"),
    KILLED("killed");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** How one Xray process ended. */
enum class Outcome(val key: String) {
    STOPPED("stopped"),
    CRASHED("crashed"),
    FAILED("failed"),
    TIMEOUT("timeout");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: FAILED
    }
}

/** What an outbound does with traffic, from its protocol. */
enum class TrafficClass(val key: String) {
    PROXY("proxy"),
    DIRECT("direct"),
    BLOCK("block"),
    OTHER("other");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: OTHER

        fun ofProtocol(protocol: String?): TrafficClass =
            when (protocol?.lowercase()) {
                "freedom" -> DIRECT
                "blackhole" -> BLOCK
                "dns",
                "loopback",
                null -> OTHER
                else -> PROXY
            }
    }
}

/**
 * A session's records are appended as they become known: a start, then a "connected" when the
 * primary core first answers, then an end. [Kind.SESSIONS] lines carry a "type" of start, connected
 * or end, and History folds them back into [Session]s.
 */
data class Session(
    val id: String,
    val mode: Mode,
    val config: String,
    val startedAt: Long,
    val connectedAt: Long? = null,
    val endedAt: Long? = null,
    val endReason: EndReason? = null,
    val endDetail: String? = null,
) {
    val timeToConnectMs: Long?
        get() = connectedAt?.let { it - startedAt }

    val isOpen: Boolean
        get() = endedAt == null

    fun durationMs(now: Long): Long = (endedAt ?: now) - startedAt
}

data class Run(
    val sessionId: String?,
    val config: String,
    val primary: Boolean,
    val startedAt: Long,
    val readyMs: Long?,
    val endedAt: Long,
    val exitCode: Int?,
    val outcome: Outcome,
    val failure: String?,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("t", startedAt)
            .putOpt("s", sessionId)
            .put("c", config)
            .put("p", primary)
            .putOpt("ready", readyMs)
            .put("end", endedAt)
            .putOpt("exit", exitCode)
            .put("o", outcome.key)
            .putOpt("why", failure)

    companion object {
        fun fromJson(o: JSONObject) =
            Run(
                sessionId = o.optStringOrNull("s"),
                config = o.optString("c"),
                primary = o.optBoolean("p"),
                startedAt = o.getLong("t"),
                readyMs = o.optLongOrNull("ready"),
                endedAt = o.optLong("end"),
                exitCode = if (o.has("exit")) o.optInt("exit") else null,
                outcome = Outcome.of(o.optStringOrNull("o")),
                failure = o.optStringOrNull("why"),
            )
    }
}

/** Bytes through one outbound of one config during one bucket (a minute, or an hour once old). */
data class Traffic(
    val bucket: Long,
    val sessionId: String?,
    val config: String,
    val outbound: String,
    val cls: TrafficClass,
    val up: Long,
    val down: Long,
) {
    val total: Long
        get() = up + down

    fun toJson(): JSONObject =
        JSONObject()
            .put("t", bucket)
            .putOpt("s", sessionId)
            .put("c", config)
            .put("o", outbound)
            .put("k", cls.key)
            .put("u", up)
            .put("d", down)

    companion object {
        fun fromJson(o: JSONObject) =
            Traffic(
                bucket = o.getLong("t"),
                sessionId = o.optStringOrNull("s"),
                config = o.optString("c"),
                outbound = o.optString("o"),
                cls = TrafficClass.of(o.optStringOrNull("k")),
                up = o.optLong("u"),
                down = o.optLong("d"),
            )
    }
}

data class Event(
    val time: Long,
    val level: Level,
    val category: Category,
    val type: String,
    val message: String,
    val sessionId: String? = null,
    val attrs: Map<String, String> = emptyMap(),
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("t", time)
            .put("l", level.name)
            .put("c", category.key)
            .put("e", type)
            .put("m", message)
            .putOpt("s", sessionId)
            .apply { if (attrs.isNotEmpty()) put("a", JSONObject(attrs)) }

    companion object {
        fun fromJson(o: JSONObject): Event {
            val a = o.optJSONObject("a")
            return Event(
                time = o.getLong("t"),
                level = runCatching { Level.valueOf(o.optString("l")) }.getOrDefault(Level.INFO),
                category =
                    Category.entries.firstOrNull { it.key == o.optString("c") } ?: Category.SESSION,
                type = o.optString("e"),
                message = o.optString("m"),
                sessionId = o.optStringOrNull("s"),
                attrs = a?.keys()?.asSequence()?.associateWith { a.optString(it) } ?: emptyMap(),
            )
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null
