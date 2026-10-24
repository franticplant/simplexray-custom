// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.ui.screens

import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.simplexray.an.R
import com.simplexray.an.common.formatBytes
import com.simplexray.an.common.formatDuration
import com.simplexray.an.telemetry.Category
import com.simplexray.an.telemetry.EndReason
import com.simplexray.an.telemetry.Event
import com.simplexray.an.telemetry.Level
import com.simplexray.an.telemetry.Mode
import com.simplexray.an.telemetry.Outcome
import com.simplexray.an.telemetry.Overview
import com.simplexray.an.telemetry.Reliability
import com.simplexray.an.telemetry.Run
import com.simplexray.an.telemetry.SessionDetail
import com.simplexray.an.telemetry.TelemetryStore.Companion.DAY_MS
import com.simplexray.an.ui.charts.ClassBar
import com.simplexray.an.ui.charts.ClassLegend
import com.simplexray.an.ui.charts.TrafficBars
import com.simplexray.an.ui.charts.breakdown
import com.simplexray.an.viewmodel.ActivityRange
import com.simplexray.an.viewmodel.ActivityState
import com.simplexray.an.viewmodel.ActivityViewModel
import com.simplexray.an.viewmodel.SessionRow
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val REFRESH_MS = 15_000L

/** Formats times the local way: "14:05", "Mon 3", "Oct 3, 14:05". */
private object Times {
    private fun format(skeleton: String, t: Long): String =
        DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton), Date(t))
            .toString()

    fun hour(t: Long) = format("Hm", t)

    fun day(t: Long) = format("EEEd", t)

    fun dateTime(t: Long) = format("MMMdHm", t)

    fun time(t: Long) = format("Hms", t)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    viewModel: ActivityViewModel,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.refresh()
                delay(REFRESH_MS)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activity_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more),
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.activity_export)) },
                            onClick = {
                                menu = false
                                exporting = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_settings)) },
                            onClick = {
                                menu = false
                                settings = true
                            },
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                ActivityRange.entries.forEachIndexed { i, range ->
                    SegmentedButton(
                        selected = state.range == range,
                        onClick = { viewModel.setRange(range) },
                        shape = SegmentedButtonDefaults.itemShape(i, ActivityRange.entries.size),
                    ) {
                        Text(
                            stringResource(
                                when (range) {
                                    ActivityRange.DAY -> R.string.range_24h
                                    ActivityRange.WEEK -> R.string.range_7d
                                    ActivityRange.MONTH -> R.string.range_30d
                                }
                            )
                        )
                    }
                }
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(R.string.tab_overview, R.string.tab_sessions, R.string.tab_events)
                    .forEachIndexed { i, title ->
                        Tab(
                            selected = tab == i,
                            onClick = { tab = i },
                            text = { Text(stringResource(title)) },
                        )
                    }
            }
            when {
                !state.historyEnabled &&
                    state.overview?.bytes?.total == 0L &&
                    state.sessions.isEmpty() ->
                    Message(stringResource(R.string.history_off_message))
                tab == 0 -> OverviewTab(state)
                tab == 1 -> SessionsTab(state, onOpenSession)
                else -> EventsTab(state.events)
            }
        }
    }

    if (exporting) {
        ExportDialog(viewModel, onDismiss = { exporting = false })
    }
    if (settings) {
        HistorySettingsDialog(
            state = state,
            onEnabled = viewModel::setHistoryEnabled,
            onDays = viewModel::setHistoryDays,
            onClear = viewModel::clearHistory,
            onDismiss = { settings = false },
        )
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun Figure(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color =
                if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OverviewTab(state: ActivityState) {
    val o = state.overview
    if (o == null) {
        Message(stringResource(R.string.loading))
        return
    }
    val label: (Long) -> String = if (o.bucketMs >= DAY_MS) Times::day else Times::hour
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(stringResource(R.string.overview_traffic)) {
                Row(Modifier.fillMaxWidth()) {
                    Figure(
                        stringResource(R.string.overview_total),
                        formatBytes(o.bytes.total),
                        Modifier.weight(1f),
                    )
                    Figure(
                        stringResource(R.string.chart_download),
                        formatBytes(o.down),
                        Modifier.weight(1f),
                    )
                    Figure(
                        stringResource(R.string.chart_upload),
                        formatBytes(o.up),
                        Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                TrafficBars(o.series, label)
                Spacer(Modifier.height(8.dp))
                ClassLegend(bytes = o.bytes)
                if (o.bytes.total > 0 && o.bytes.proxy == 0L) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.overview_no_proxy_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        item { ReliabilityCard(o.reliability, o.connectedMs) }
        item { ConfigsCard(o) }
    }
}

@Composable
private fun ReliabilityCard(r: Reliability, connectedMs: Long) {
    SectionCard(stringResource(R.string.overview_reliability)) {
        Row(Modifier.fillMaxWidth()) {
            Figure(
                stringResource(R.string.overview_success_rate),
                r.successRate?.let { "${(it * 100).toInt()}%" } ?: "–",
                Modifier.weight(1f),
                warn = (r.successRate ?: 1.0) < 0.9,
            )
            Figure(
                stringResource(R.string.overview_time_to_connect),
                r.medianTimeToConnectMs?.let { formatMs(it) } ?: "–",
                Modifier.weight(1f),
            )
            Figure(
                stringResource(R.string.overview_connected_time),
                formatDuration(connectedMs),
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Figure(
                stringResource(R.string.overview_sessions),
                pluralStringResource(
                    R.plurals.overview_connected_of,
                    r.sessions,
                    r.connected,
                    r.sessions,
                ),
                Modifier.weight(1f),
            )
            Figure(
                stringResource(R.string.overview_crashes),
                r.crashes.toString(),
                Modifier.weight(1f),
                warn = r.crashes > 0,
            )
            Figure(
                stringResource(R.string.overview_failed_starts),
                r.failedRuns.toString(),
                Modifier.weight(1f),
                warn = r.failedRuns > 0,
            )
        }
        r.p90TimeToConnectMs?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.overview_p90, formatMs(it)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (r.endReasons.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.overview_how_sessions_ended),
                style = MaterialTheme.typography.labelLarge,
            )
            r.endReasons.entries
                .sortedByDescending { it.value }
                .forEach { (reason, count) ->
                    KeyValue(endReasonText(reason), count.toString())
                }
        }
        if (r.topFailures.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.overview_top_failures),
                style = MaterialTheme.typography.labelLarge,
            )
            r.topFailures.forEach { (reason, count) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        reason,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("×$count", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ConfigsCard(o: Overview) {
    SectionCard(stringResource(R.string.overview_by_config)) {
        if (o.byConfig.isEmpty()) {
            Text(
                stringResource(R.string.overview_nothing_yet),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val max = o.byConfig.maxOfOrNull { it.bytes.total } ?: 0
        o.byConfig.forEach { c ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        c.config,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatBytes(c.bytes.total)} · ${formatDuration(c.connectedMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                ClassBar(c.bytes, max)
            }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SessionsTab(state: ActivityState, onOpen: (String) -> Unit) {
    if (state.sessions.isEmpty()) {
        Message(stringResource(R.string.sessions_empty))
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(state.sessions, key = { it.session.id }) { row ->
            SessionItem(row, onClick = { onOpen(row.session.id) })
            HorizontalDivider()
        }
    }
}

@Composable
fun SessionItem(row: SessionRow, onClick: (() -> Unit)?) {
    val s = row.session
    val now = System.currentTimeMillis()
    Column(
        Modifier.fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.config,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusText(row)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            listOfNotNull(
                    Times.dateTime(s.startedAt),
                    formatDuration(s.durationMs(now)),
                    formatBytes(row.bytes.total),
                    s.timeToConnectMs?.let {
                        stringResource(R.string.session_connected_in, formatMs(it))
                    },
                    if (s.mode == Mode.CORE_ONLY) stringResource(R.string.session_core_only)
                    else null,
                )
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusText(row: SessionRow) {
    val s = row.session
    val (text, color) =
        when {
            row.failed ->
                stringResource(R.string.session_core_failed) to MaterialTheme.colorScheme.error
            row.live && s.connectedAt == null ->
                stringResource(R.string.session_connecting) to
                    MaterialTheme.colorScheme.onSurfaceVariant
            row.live -> stringResource(R.string.session_live) to Color(0xFF2E7D32)
            s.isOpen ->
                stringResource(R.string.session_interrupted) to MaterialTheme.colorScheme.error
            s.connectedAt == null ->
                stringResource(R.string.session_never_connected) to MaterialTheme.colorScheme.error
            else ->
                endReasonText(s.endReason ?: EndReason.STOPPED) to
                    if (s.endReason == EndReason.USER) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.tertiary
        }
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

@Composable
private fun endReasonText(reason: EndReason): String =
    stringResource(
        when (reason) {
            EndReason.USER -> R.string.end_user
            EndReason.REVOKED -> R.string.end_revoked
            EndReason.FAILED -> R.string.end_failed
            EndReason.STOPPED -> R.string.end_stopped
            EndReason.KILLED -> R.string.end_killed
        }
    )

private enum class LevelFilter {
    ALL,
    WARNINGS,
    ERRORS,
}

@Composable
private fun EventsTab(events: List<Event>) {
    var level by rememberSaveable { mutableStateOf(LevelFilter.ALL) }
    var category by rememberSaveable { mutableStateOf<Category?>(null) }
    val shown = events.filter { e ->
        (category == null || e.category == category) &&
            when (level) {
                LevelFilter.ALL -> true
                LevelFilter.WARNINGS -> e.level != Level.INFO
                LevelFilter.ERRORS -> e.level == Level.ERROR
            }
    }
    Column {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = level == LevelFilter.WARNINGS,
                onClick = {
                    level =
                        if (level == LevelFilter.WARNINGS) LevelFilter.ALL else LevelFilter.WARNINGS
                },
                label = { Text(stringResource(R.string.events_problems)) },
            )
            FilterChip(
                selected = level == LevelFilter.ERRORS,
                onClick = {
                    level = if (level == LevelFilter.ERRORS) LevelFilter.ALL else LevelFilter.ERRORS
                },
                label = { Text(stringResource(R.string.events_errors)) },
            )
            Category.entries.forEach { c ->
                FilterChip(
                    selected = category == c,
                    onClick = { category = if (category == c) null else c },
                    label = { Text(categoryText(c)) },
                )
            }
        }
        if (shown.isEmpty()) {
            Message(stringResource(R.string.events_empty))
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                eventItems(shown, Times::dateTime)
            }
        }
    }
}

private fun LazyListScope.eventItems(events: List<Event>, time: (Long) -> String) {
    items(events) { e -> EventItem(e, time) }
}

@Composable
private fun EventItem(e: Event, time: (Long) -> String) {
    val color =
        when (e.level) {
            Level.ERROR -> MaterialTheme.colorScheme.error
            Level.WARN -> MaterialTheme.colorScheme.tertiary
            Level.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            time(e.time),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp, top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                e.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (e.level == Level.INFO) MaterialTheme.colorScheme.onSurface else color,
            )
            Text(
                categoryText(e.category),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

@Composable
private fun categoryText(c: Category): String =
    stringResource(
        when (c) {
            Category.SESSION -> R.string.category_session
            Category.INSTANCE -> R.string.category_instance
            Category.NETWORK -> R.string.category_network
            Category.CONNECTIVITY -> R.string.category_connectivity
            Category.CONFIG -> R.string.category_config
            Category.PERMISSION -> R.string.category_permission
            Category.HISTORY -> R.string.category_history
        }
    )

@Composable
private fun HistorySettingsDialog(
    state: ActivityState,
    onEnabled: (Boolean) -> Unit,
    onDays: (Int) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_settings)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.history_record))
                        Text(
                            stringResource(R.string.history_record_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.historyEnabled, onCheckedChange = onEnabled)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.history_keep_for),
                    style = MaterialTheme.typography.labelLarge,
                )
                listOf(7, 30, 90, 365).forEach { days ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onDays(days) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.historyDays == days,
                            onClick = { onDays(days) },
                        )
                        Text(pluralStringResource(R.plurals.history_days, days, days))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.history_storage, formatBytes(state.storageBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { confirmClear = true }) {
                    Text(
                        stringResource(R.string.history_clear),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear)) },
            text = { Text(stringResource(R.string.history_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    }
                ) {
                    Text(
                        stringResource(R.string.history_clear),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ExportDialog(viewModel: ActivityViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var range by remember { mutableStateOf<ActivityRange?>(ActivityRange.WEEK) }
    var redact by remember { mutableStateOf(true) }
    var logs by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val failed = stringResource(R.string.export_failed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.export_range),
                    style = MaterialTheme.typography.labelLarge,
                )
                (ActivityRange.entries + listOf(null)).forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { range = r },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = range == r, onClick = { range = r })
                        Text(
                            stringResource(
                                when (r) {
                                    ActivityRange.DAY -> R.string.range_24h
                                    ActivityRange.WEEK -> R.string.range_7d
                                    ActivityRange.MONTH -> R.string.range_30d
                                    null -> R.string.export_everything
                                }
                            )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.export_redact))
                        Text(
                            stringResource(R.string.export_redact_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = redact, onCheckedChange = { redact = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.export_logs), Modifier.weight(1f))
                    Switch(checked = logs, onCheckedChange = { logs = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val intent = viewModel.export(range?.ms, redact, logs)
                        busy = false
                        onDismiss()
                        if (intent != null) context.startActivity(intent)
                        else Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                    }
                },
            ) {
                Text(stringResource(R.string.activity_export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(viewModel: ActivityViewModel, sessionId: String, onBack: () -> Unit) {
    val detail by viewModel.detail.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(sessionId) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // A session still going gains traffic and events; an ended one is loaded once.
            do {
                viewModel.openSession(sessionId)
                delay(REFRESH_MS)
            } while (viewModel.detail.value?.session?.isOpen != false)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.session?.config ?: stringResource(R.string.session_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        }
    ) { padding ->
        val d = detail
        if (d == null || d.session.id != sessionId) {
            Column(Modifier.padding(padding)) { Message(stringResource(R.string.loading)) }
            return@Scaffold
        }
        SessionDetailContent(d, Modifier.padding(padding))
    }
}

@Composable
private fun SessionDetailContent(d: SessionDetail, modifier: Modifier) {
    val s = d.session
    val now = System.currentTimeMillis()
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(stringResource(R.string.session_title)) {
                KeyValue(stringResource(R.string.session_started), Times.dateTime(s.startedAt))
                s.endedAt?.let {
                    KeyValue(stringResource(R.string.session_ended), Times.dateTime(it))
                }
                KeyValue(
                    stringResource(R.string.session_duration),
                    formatDuration(s.durationMs(now)),
                )
                KeyValue(
                    stringResource(R.string.overview_time_to_connect),
                    s.timeToConnectMs?.let { formatMs(it) }
                        ?: stringResource(R.string.session_never_connected),
                )
                KeyValue(
                    stringResource(R.string.session_mode),
                    stringResource(
                        if (s.mode == Mode.VPN) R.string.session_vpn else R.string.session_core_only
                    ),
                )
                s.endReason?.let {
                    KeyValue(stringResource(R.string.session_end_reason), endReasonText(it))
                }
                s.endDetail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.overview_traffic)) {
                Text(breakdown(d.bytes), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "↓ ${formatBytes(d.down)}  ↑ ${formatBytes(d.up)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                TrafficBars(d.series, Times::hour, height = 120.dp)
                Spacer(Modifier.height(8.dp))
                ClassLegend()
            }
        }
        if (d.runs.isNotEmpty()) {
            item {
                SectionCard(stringResource(R.string.session_runs)) {
                    d.runs.forEach { RunItem(it) }
                }
            }
        }
        if (d.events.isNotEmpty()) {
            item {
                SectionCard(stringResource(R.string.tab_events)) {
                    d.events.sortedBy { it.time }.forEach { EventItem(it, Times::time) }
                }
            }
        }
    }
}

@Composable
private fun RunItem(r: Run) {
    val color =
        when (r.outcome) {
            Outcome.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
            Outcome.CRASHED -> MaterialTheme.colorScheme.tertiary
            Outcome.FAILED,
            Outcome.TIMEOUT -> MaterialTheme.colorScheme.error
        }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row {
            Text(
                r.config + if (r.primary) " · VPN" else "",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    when (r.outcome) {
                        Outcome.STOPPED -> R.string.run_stopped
                        Outcome.CRASHED -> R.string.run_crashed
                        Outcome.FAILED -> R.string.run_failed
                        Outcome.TIMEOUT -> R.string.run_timeout
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
        }
        Text(
            listOfNotNull(
                    "${Times.time(r.startedAt)}–${Times.time(r.endedAt)}",
                    r.readyMs?.let { stringResource(R.string.run_ready_in, formatMs(it)) },
                    r.exitCode?.let { stringResource(R.string.run_exit_code, it) },
                )
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        r.failure?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = color,
            )
        }
    }
}

/** Milliseconds under 10 s as "850 ms" or "2.4 s"; longer as a duration. */
fun formatMs(ms: Long): String =
    when {
        ms < 1000 -> "$ms ms"
        ms < 10_000 -> "%.1f s".format(ms / 1000.0)
        else -> formatDuration(ms)
    }
