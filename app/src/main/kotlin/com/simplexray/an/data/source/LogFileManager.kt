// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.data.source

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The log, one file per session in `files/logs/`, named by when the session started
 * (`yyyyMMdd-HHmmss_<session id>.txt`, UTC), so they sort by age. The last [MAX_SESSIONS] are kept,
 * and each is capped at [MAX_LOG_SIZE_BYTES] by dropping its older half.
 *
 * The service writes; the app reads the newest file as the current log and older ones on request.
 */
class LogFileManager(context: Context) {
    private val dir = File(context.filesDir, DIR)
    @Volatile private var current: File? = null

    init {
        // The single log of earlier versions.
        File(context.filesDir, LEGACY_FILE_NAME).delete()
    }

    /** The log being written, or the newest one. */
    val logFile: File
        get() = current ?: sessionFiles().firstOrNull() ?: File(dir, "none.txt")

    /** Session logs, newest first. */
    fun sessionFiles(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            .orEmpty()
            .sortedByDescending { it.name }

    /** Starts a new log file for session [id]; older ones beyond the limit are deleted. */
    @Synchronized
    fun startSession(id: String, now: Long = System.currentTimeMillis()) {
        dir.mkdirs()
        current = File(dir, "${fileStamp(now)}_$id.txt").also { it.createNewFile() }
        sessionFiles().drop(MAX_SESSIONS).forEach { it.delete() }
    }

    @Synchronized
    fun appendLog(logEntry: String?) {
        if (logEntry == null) return
        if (current == null) startSession("log")
        val file = current ?: return
        try {
            FileOutputStream(file, true).use { it.write((logEntry + "\n").toByteArray()) }
        } catch (e: IOException) {
            Log.e(TAG, "Error appending log to file", e)
        }
        if (file.length() > MAX_LOG_SIZE_BYTES) dropOlderHalf(file)
    }

    fun readLogs(file: File = logFile): String? {
        if (!file.exists()) return ""
        return try {
            file.readText()
        } catch (e: IOException) {
            Log.e(TAG, "Error reading log file", e)
            null
        }
    }

    /** Empties the current log (the Log screen's clear); older sessions' logs are kept. */
    @Synchronized
    fun clearLogs() {
        val file = logFile
        if (!file.exists()) return
        try {
            file.writeText("")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to clear log file content.", e)
        }
    }

    /** Keeps the newer half of [file], from the start of a line. */
    private fun dropOlderHalf(file: File) {
        try {
            val keepFrom = file.length() - MAX_LOG_SIZE_BYTES / 2
            val tail =
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(keepFrom)
                    raf.readLine() // the rest of a cut line
                    val start = raf.filePointer
                    ByteArray((raf.length() - start).toInt()).also { raf.readFully(it) }
                }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(tail)
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: IOException) {
            Log.e(TAG, "Error during log file truncation", e)
            file.writeText("")
        }
    }

    companion object {
        private const val TAG = "LogFileManager"
        private const val DIR = "logs"
        private const val LEGACY_FILE_NAME = "app_log.txt"
        const val MAX_SESSIONS = 20
        const val MAX_LOG_SIZE_BYTES = 5L * 1024 * 1024

        private val XRAY_TIME = Regex("""^\d{4}/\d\d/\d\d \d\d:\d\d:\d\d(\.\d+)?\s+""")

        /**
         * A log line as stored: the time it was logged, the config it came from, and the line
         * without Xray's own timestamp, which the first one replaces.
         */
        fun format(
            tag: String,
            line: String,
            now: Long,
            timeZone: TimeZone = TimeZone.getDefault(),
        ): String {
            val time =
                SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                    .apply { this.timeZone = timeZone }
                    .format(Date(now))
            return "$time [$tag] ${line.replace(XRAY_TIME, "")}"
        }

        /** The config a stored line came from: the bracketed name after its time. */
        fun configOf(line: String): String? =
            line.substringAfter(" [", "").substringBefore("] ", "").takeIf { it.isNotEmpty() }

        /** Whether a stored line reports a warning or an error. */
        fun isProblem(line: String): Boolean = PROBLEM.containsMatchIn(line)

        private val PROBLEM =
            Regex(
                """\[(Warning|Error)]|\b(fail(ed|ure)?|panic|fatal|error)\b""",
                RegexOption.IGNORE_CASE,
            )

        /** When a session log's session started, from its file name. */
        fun startedAt(file: File): Long? = runCatching {
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(file.name.take(15))
                ?.time
        }
            .getOrNull()

        private fun fileStamp(now: Long) =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(now))
    }
}
