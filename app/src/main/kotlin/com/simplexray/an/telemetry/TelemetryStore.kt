// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.telemetry

import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import org.json.JSONException
import org.json.JSONObject

/**
 * Append-only JSON-lines files, one per kind, UTC day and writing process:
 * `<root>/<kind>/<yyyy-MM-dd>.<process>[.hourly].jsonl`. Every record has its time in "t", which
 * picks its day file.
 *
 * Each process appends only to its own files, so the app and the service never write to one file.
 * Day files make expiry exact (a whole old day is deleted) and export a copy. A line cut short by
 * the process being killed is skipped when reading.
 */
class TelemetryStore(val root: File, private val process: String) {
    @Synchronized
    fun append(kind: Kind, records: List<JSONObject>) {
        if (records.isEmpty()) return
        records
            .groupBy { day(it.getLong("t")) }
            .forEach { (day, lines) ->
                val file = File(dir(kind), "$day.$process.jsonl")
                file.parentFile?.mkdirs()
                FileOutputStream(file, true).use { out ->
                    out.write(lines.joinToString("") { "$it\n" }.toByteArray())
                }
            }
    }

    fun append(kind: Kind, record: JSONObject) = append(kind, listOf(record))

    /** Records of [kind] with from <= t < to, from every process, oldest day first. */
    fun read(kind: Kind, from: Long, to: Long): List<JSONObject> =
        files(kind, from, to).flatMap { file ->
            val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
            lines.mapNotNull { line ->
                try {
                    JSONObject(line).takeIf { it.optLong("t", -1) in from until to }
                } catch (e: JSONException) {
                    null // cut short by a kill
                }
            }
        }

    /** The day files of [kind] that can hold records with from <= t < to. */
    fun files(kind: Kind, from: Long, to: Long): List<File> {
        val first = day(from)
        val last = day(to - 1)
        return dir(kind)
            .listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            .orEmpty()
            .filter { f -> dayOf(f)?.let { it >= first && it <= last } == true }
            .sortedBy { it.name }
    }

    /** Deletes every day file older than [keepDays] days before [now]'s day. */
    @Synchronized
    fun expire(now: Long, keepDays: Int) {
        val oldest = day(now).minusDays(keepDays.toLong() - 1)
        Kind.entries.forEach { kind ->
            dir(kind)
                .listFiles()
                .orEmpty()
                .filter { f -> dayOf(f)?.isBefore(oldest) == true }
                .forEach { it.delete() }
        }
    }

    /**
     * Rewrites this process's traffic files from days before the last [minuteDays] at hour
     * resolution. A rewritten file is renamed to .hourly, so it is done once.
     */
    @Synchronized
    fun compactTraffic(now: Long, minuteDays: Int = 2) {
        val firstMinuteDay = day(now).minusDays(minuteDays.toLong() - 1)
        dir(Kind.TRAFFIC)
            .listFiles { f -> f.name.endsWith(".$process.jsonl") }
            .orEmpty()
            .filter { f -> dayOf(f)?.isBefore(firstMinuteDay) == true }
            .forEach { file ->
                val rows =
                    file.readLines().mapNotNull { line ->
                        runCatching { Traffic.fromJson(JSONObject(line)) }.getOrNull()
                    }
                val hourly =
                    rows
                        .groupBy {
                            listOf(
                                it.bucket / HOUR_MS,
                                it.sessionId,
                                it.config,
                                it.outbound,
                                it.cls,
                            )
                        }
                        .map { (_, group) ->
                            group
                                .first()
                                .copy(
                                    bucket = group.first().bucket / HOUR_MS * HOUR_MS,
                                    up = group.sumOf { it.up },
                                    down = group.sumOf { it.down },
                                )
                        }
                        .sortedBy { it.bucket }
                val out = File(file.parentFile, file.name.removeSuffix(".jsonl") + ".hourly.jsonl")
                val tmp = File(file.parentFile, out.name + ".tmp")
                tmp.writeText(hourly.joinToString("") { "${it.toJson()}\n" })
                if (tmp.renameTo(out)) file.delete() else tmp.delete()
            }
    }

    @Synchronized
    fun clear() {
        root.deleteRecursively()
    }

    fun sizeBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun dir(kind: Kind) = File(root, kind.dir)

    companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L

        fun day(time: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(time, DAY_MS))

        private fun dayOf(file: File): LocalDate? = runCatching {
            LocalDate.parse(file.name.take(10))
        }
            .getOrNull()
    }
}
