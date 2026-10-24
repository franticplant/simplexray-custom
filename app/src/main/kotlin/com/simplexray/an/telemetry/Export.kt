// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.telemetry

import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Writes a period of history as one zip (docs/observability.md, "Export"). */
object Export {
    data class Options(
        val from: Long,
        val to: Long,
        val now: Long,
        val zone: ZoneId,
        val redact: Boolean,
        /** App, Xray-core and Android versions, and the retention setting, for manifest.json. */
        val about: Map<String, String>,
        /** Session logs to include, by the name they get in logs/. */
        val logs: Map<String, File> = emptyMap(),
    )

    fun write(store: TelemetryStore, options: Options, out: OutputStream) {
        val o = options
        val clean: (String) -> String = if (o.redact) Redactor::redact else { s -> s }
        val sessions = History.sessions(store, o.from, o.to)
        val runs = History.runs(store, o.from, o.to)
        val traffic = History.traffic(store, o.from, o.to)
        val events = History.events(store, o.from, o.to).sortedBy { it.time }
        val overview =
            History.overview(
                o.from,
                o.to,
                o.now,
                TelemetryStore.DAY_MS,
                o.zone,
                traffic,
                sessions,
                runs,
            )
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(o.zone)
        fun time(t: Long?) = t?.let { iso.format(Instant.ofEpochMilli(it)) } ?: ""

        ZipOutputStream(out).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }

            entry(
                "manifest.json",
                JSONObject()
                    .put("format", 1)
                    .put("exported_at", time(o.now))
                    .put("from", time(o.from))
                    .put("to", time(o.to))
                    .put("redacted", o.redact)
                    .apply { o.about.forEach { (k, v) -> put(k, v) } }
                    .toString(2),
            )
            entry("summary.txt", summary(overview, sessions.size, o, ::time))

            val bySession = traffic.groupBy { it.sessionId }
            entry(
                "sessions.csv",
                csv(
                    listOf(
                        "id",
                        "config",
                        "mode",
                        "started",
                        "connected_after_ms",
                        "ended",
                        "duration_s",
                        "end_reason",
                        "end_detail",
                        "up_bytes",
                        "down_bytes",
                        "proxy_bytes",
                        "direct_bytes",
                        "block_bytes",
                        "other_bytes",
                    ),
                    sessions
                        .sortedBy { it.startedAt }
                        .map { s ->
                            val rows = bySession[s.id].orEmpty()
                            val c = rows.fold(ClassTotals()) { a, t -> a + t }
                            listOf(
                                s.id,
                                clean(s.config),
                                s.mode.key,
                                time(s.startedAt),
                                s.timeToConnectMs?.toString() ?: "",
                                time(s.endedAt),
                                (s.durationMs(o.now) / 1000).toString(),
                                s.endReason?.key ?: "",
                                s.endDetail?.let(clean) ?: "",
                                rows.sumOf { it.up }.toString(),
                                rows.sumOf { it.down }.toString(),
                                c.proxy.toString(),
                                c.direct.toString(),
                                c.block.toString(),
                                c.other.toString(),
                            )
                        },
                ),
            )
            entry(
                "runs.csv",
                csv(
                    listOf(
                        "session",
                        "config",
                        "primary",
                        "started",
                        "ready_ms",
                        "ended",
                        "exit_code",
                        "outcome",
                        "failure",
                    ),
                    runs
                        .sortedBy { it.startedAt }
                        .map { r ->
                            listOf(
                                r.sessionId ?: "",
                                clean(r.config),
                                r.primary.toString(),
                                time(r.startedAt),
                                r.readyMs?.toString() ?: "",
                                time(r.endedAt),
                                r.exitCode?.toString() ?: "",
                                r.outcome.key,
                                r.failure?.let(clean) ?: "",
                            )
                        },
                ),
            )
            entry(
                "traffic_hourly.csv",
                csv(
                    listOf(
                        "hour",
                        "session",
                        "config",
                        "outbound",
                        "class",
                        "up_bytes",
                        "down_bytes",
                    ),
                    traffic
                        .groupBy {
                            listOf(
                                it.bucket / TelemetryStore.HOUR_MS,
                                it.sessionId,
                                it.config,
                                it.outbound,
                                it.cls,
                            )
                        }
                        .map { (key, rows) ->
                            val r = rows.first()
                            listOf(
                                time((key[0] as Long) * TelemetryStore.HOUR_MS),
                                r.sessionId ?: "",
                                clean(r.config),
                                r.outbound,
                                r.cls.key,
                                rows.sumOf { it.up }.toString(),
                                rows.sumOf { it.down }.toString(),
                            )
                        }
                        .sortedBy { it[0] },
                ),
            )
            entry(
                "events.jsonl",
                events.joinToString("") { e ->
                    val redacted =
                        e.copy(
                            message = clean(e.message),
                            attrs = e.attrs.mapValues { clean(it.value) },
                        )
                    redacted.toJson().put("time", time(e.time)).toString() + "\n"
                },
            )
            o.logs.forEach { (name, file) ->
                if (!file.isFile) return@forEach
                zip.putNextEntry(ZipEntry("logs/$name"))
                file.bufferedReader().useLines { lines ->
                    lines.forEach { zip.write((clean(it) + "\n").toByteArray()) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun summary(o: Overview, sessions: Int, opt: Options, time: (Long?) -> String): String {
        val r = o.reliability
        fun mb(b: Long) = "%.1f MB".format(b / 1_048_576.0)
        return buildString {
            appendLine("SimpleXray activity, ${time(opt.from)} to ${time(opt.to)}")
            if (opt.redact) appendLine("Addresses, domains and UUIDs are redacted.")
            appendLine()
            appendLine("Traffic: ${mb(o.bytes.total)} (down ${mb(o.down)}, up ${mb(o.up)})")
            appendLine(
                "  proxied ${mb(o.bytes.proxy)}, direct ${mb(o.bytes.direct)}, " +
                    "blocked ${mb(o.bytes.block)}, other ${mb(o.bytes.other)}"
            )
            appendLine("Connected for ${o.connectedMs / 60_000} min over $sessions sessions")
            appendLine()
            appendLine("Reliability")
            appendLine(
                "  sessions that connected: ${r.connected} of ${r.sessions}" +
                    (r.successRate?.let { " (${(it * 100).toInt()}%)" } ?: "")
            )
            r.medianTimeToConnectMs?.let {
                appendLine(
                    "  time to connect: median $it ms, 90th percentile ${r.p90TimeToConnectMs} ms"
                )
            }
            appendLine("  core crashes: ${r.crashes}, failed starts: ${r.failedRuns}")
            r.endReasons.forEach { (reason, n) -> appendLine("  ended ${reason.key}: $n") }
            if (r.topFailures.isNotEmpty()) {
                appendLine("  most common failures:")
                r.topFailures.forEach { (why, n) ->
                    appendLine("    $n× " + if (opt.redact) Redactor.redact(why) else why)
                }
            }
            appendLine()
            appendLine("By config")
            o.byConfig.forEach { c ->
                val name = if (opt.redact) Redactor.redact(c.config) else c.config
                appendLine("  $name: ${mb(c.bytes.total)}, connected ${c.connectedMs / 60_000} min")
            }
        }
    }

    /** RFC 4180: fields with a comma, quote or line break are quoted, quotes doubled. */
    internal fun csv(header: List<String>, rows: List<List<String>>): String =
        (listOf(header) + rows).joinToString("") { row ->
            row.joinToString(",") { f ->
                if (f.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
                    "\"" + f.replace("\"", "\"\"") + "\""
                else f
            } + "\r\n"
        }
}
