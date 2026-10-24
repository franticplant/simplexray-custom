// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simplexray.an.BuildConfig
import com.simplexray.an.R
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.service.TProxyService
import com.simplexray.an.telemetry.Category
import com.simplexray.an.telemetry.ClassTotals
import com.simplexray.an.telemetry.Event
import com.simplexray.an.telemetry.Export
import com.simplexray.an.telemetry.History
import com.simplexray.an.telemetry.Level
import com.simplexray.an.telemetry.Overview
import com.simplexray.an.telemetry.Session
import com.simplexray.an.telemetry.SessionDetail
import com.simplexray.an.telemetry.Telemetry
import com.simplexray.an.telemetry.TelemetryStore.Companion.DAY_MS
import com.simplexray.an.telemetry.TelemetryStore.Companion.HOUR_MS
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The periods the Activity screen can show, and the bar each one is drawn with. */
enum class ActivityRange(val ms: Long, val bucketMs: Long) {
    DAY(DAY_MS, HOUR_MS),
    WEEK(7 * DAY_MS, DAY_MS),
    MONTH(30 * DAY_MS, DAY_MS),
}

/** A session as listed: with its data and whether it is still going. */
data class SessionRow(
    val session: Session,
    val bytes: ClassTotals,
    val live: Boolean,
    /** Its core failed to start before it connected. */
    val failed: Boolean = false,
)

data class ActivityState(
    val range: ActivityRange = ActivityRange.DAY,
    val loading: Boolean = true,
    val overview: Overview? = null,
    val sessions: List<SessionRow> = emptyList(),
    val events: List<Event> = emptyList(),
    val historyEnabled: Boolean = true,
    val historyDays: Int = 30,
    val storageBytes: Long = 0,
)

/** Today's figures for the Dashboard. */
data class TodayState(
    val bytes: ClassTotals = ClassTotals(),
    val connectedMs: Long = 0,
    val sessions: Int = 0,
    val current: SessionRow? = null,
)

class ActivityViewModel(application: Application) : AndroidViewModel(application) {
    private val store = Telemetry.store(application, Telemetry.APP)
    private val prefs = Preferences(application)

    private val _state = MutableStateFlow(ActivityState())
    val state: StateFlow<ActivityState> = _state.asStateFlow()

    private val _today = MutableStateFlow(TodayState())
    val today: StateFlow<TodayState> = _today.asStateFlow()

    private val _detail = MutableStateFlow<SessionDetail?>(null)
    val detail: StateFlow<SessionDetail?> = _detail.asStateFlow()

    fun setRange(range: ActivityRange) {
        _state.update { it.copy(range = range) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { load(_state.value.range) }
        }
    }

    fun refreshToday() {
        viewModelScope.launch {
            _today.value = withContext(Dispatchers.IO) { loadToday() }
        }
    }

    fun openSession(id: String) {
        if (_detail.value?.session?.id != id) _detail.value = null
        viewModelScope.launch {
            _detail.value =
                withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val session =
                        History.sessions(store, now - 400 * DAY_MS, now + 1).firstOrNull {
                            it.id == id
                        } ?: return@withContext null
                    History.sessionDetail(store, session, now, ZoneId.systemDefault())
                }
        }
    }

    fun setHistoryEnabled(enabled: Boolean) {
        prefs.historyEnabled = enabled
        _state.update { it.copy(historyEnabled = enabled) }
    }

    fun setHistoryDays(days: Int) {
        prefs.historyDays = days
        _state.update { it.copy(historyDays = days) }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            store.clear()
            Telemetry.event(
                getApplication(),
                Level.INFO,
                Category.HISTORY,
                "history.cleared",
                "History cleared",
            )
            refresh()
            refreshToday()
        }
    }

    /**
     * Writes an export of the last [rangeMs] (everything kept if null) and returns the intent that
     * shares it, or null if it failed.
     */
    suspend fun export(rangeMs: Long?, redact: Boolean, includeLogs: Boolean): Intent? =
        withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            val now = System.currentTimeMillis()
            val from = if (rangeMs == null) now - prefs.historyDays * DAY_MS else now - rangeMs
            val dir = File(app.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // only the latest export is kept
            val day = LocalDate.now(ZoneId.systemDefault())
            val file = File(dir, "simplexray-activity-$day.zip")
            try {
                val logs =
                    if (includeLogs) Telemetry.sessionLogs(app).associateBy { it.name }
                    else emptyMap()
                file.outputStream().use { out ->
                    Export.write(
                        store,
                        Export.Options(
                            from = from,
                            to = now + 1,
                            now = now,
                            zone = ZoneId.systemDefault(),
                            redact = redact,
                            about =
                                mapOf(
                                    "app_version" to BuildConfig.VERSION_NAME,
                                    "xray_version" to Telemetry.xrayVersion(app),
                                    "android" to
                                        "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                                    "history_days" to prefs.historyDays.toString(),
                                ),
                            logs = logs,
                        ),
                        out,
                    )
                }
                val uri =
                    FileProvider.getUriForFile(
                        app,
                        "${BuildConfig.APPLICATION_ID}.fileprovider",
                        file,
                    )
                val send =
                    Intent(Intent.ACTION_SEND)
                        .setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Intent.createChooser(send, app.getString(R.string.export_chooser))
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                null
            }
        }

    private fun load(range: ActivityRange): ActivityState {
        val now = System.currentTimeMillis()
        val from = now - range.ms
        val to = now + 1
        val zone = ZoneId.systemDefault()
        val traffic = History.traffic(store, from, to)
        val sessions = History.sessions(store, from, to)
        val runs = History.runs(store, from, to)
        val overview =
            History.overview(from, to, now, range.bucketMs, zone, traffic, sessions, runs)
        val bySession =
            traffic
                .groupBy { it.sessionId }
                .mapValues { (_, rows) ->
                    rows.fold(ClassTotals()) { a, t -> a + t }
                }
        return ActivityState(
            range = range,
            loading = false,
            overview = overview,
            sessions = rows(sessions, bySession, History.failedToStart(runs)),
            events = History.events(store, from, to).take(MAX_EVENTS),
            historyEnabled = prefs.historyEnabled,
            historyDays = prefs.historyDays,
            storageBytes = store.sizeBytes(),
        )
    }

    private fun loadToday(): TodayState {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val midnight = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val traffic = History.traffic(store, midnight, now + 1)
        val sessions = History.sessions(store, midnight, now + 1)
        val overview =
            History.overview(midnight, now + 1, now, DAY_MS, zone, traffic, sessions, emptyList())
        // The current session may have started before midnight.
        val current =
            rows(History.sessions(store, now - 2 * DAY_MS, now + 1), emptyMap()).firstOrNull {
                it.live
            }
        val currentBytes = current?.let { row ->
            History.traffic(store, row.session.startedAt - HOUR_MS, now + 1)
                .filter { it.sessionId == row.session.id }
                .fold(ClassTotals()) { a, t -> a + t }
        }
        return TodayState(
            bytes = overview.bytes,
            connectedMs = overview.connectedMs,
            sessions = sessions.size,
            current = current?.copy(bytes = currentBytes ?: ClassTotals()),
        )
    }

    /** Sessions with their data; the newest open one is live while the service runs. */
    private fun rows(
        sessions: List<Session>,
        bytes: Map<String?, ClassTotals>,
        failed: Set<String> = emptySet(),
    ): List<SessionRow> {
        val serviceOn = serviceRunning()
        return sessions.mapIndexed { i, s ->
            SessionRow(
                s,
                bytes[s.id] ?: ClassTotals(),
                live = i == 0 && s.isOpen && serviceOn,
                failed = s.connectedAt == null && s.id in failed,
            )
        }
    }

    /**
     * Whether the VPN service is running now. The saved "enabled" setting is not enough: it stays
     * set when the service's process is killed.
     */
    @Suppress("DEPRECATION") // still returns the caller's own services
    private fun serviceRunning(): Boolean {
        val am = getApplication<Application>().getSystemService(ActivityManager::class.java)
        return am?.getRunningServices(Int.MAX_VALUE)?.any {
            it.service.className == TProxyService::class.java.name
        } == true
    }

    private companion object {
        const val TAG = "ActivityViewModel"
        const val MAX_EVENTS = 500
    }
}
