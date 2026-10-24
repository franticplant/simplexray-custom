// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.an.R
import com.simplexray.an.data.source.LogFileManager
import com.simplexray.an.ui.theme.ScrollbarDefaults
import com.simplexray.an.viewmodel.LogViewModel
import java.io.File
import java.util.Locale
import my.nanihadesuka.compose.LazyColumnScrollbar

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    logViewModel: LogViewModel,
    listState: LazyListState,
) {
    val context = LocalContext.current
    val filteredEntries by logViewModel.filteredEntries.collectAsStateWithLifecycle()
    val problemsOnly by logViewModel.problemsOnly.collectAsStateWithLifecycle()
    val configFilter by logViewModel.configFilter.collectAsStateWithLifecycle()
    val configs by logViewModel.configs.collectAsStateWithLifecycle()
    val sessionFiles by logViewModel.sessionFiles.collectAsStateWithLifecycle()
    val selectedFile by logViewModel.selectedFile.collectAsStateWithLifecycle()
    val isInitialLoad = remember { mutableStateOf(true) }

    DisposableEffect(key1 = Unit) {
        logViewModel.registerLogReceiver(context)
        logViewModel.loadLogs()
        onDispose {
            logViewModel.unregisterLogReceiver(context)
        }
    }

    LaunchedEffect(filteredEntries) {
        if (filteredEntries.isNotEmpty() && isInitialLoad.value) {
            listState.animateScrollToItem(0)
            isInitialLoad.value = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LogFilters(
            sessionFiles = sessionFiles,
            selectedFile = selectedFile,
            onSession = logViewModel::selectSession,
            problemsOnly = problemsOnly,
            onProblemsOnly = logViewModel::setProblemsOnly,
            configs = configs,
            config = configFilter,
            onConfig = logViewModel::setConfigFilter,
        )
        if (filteredEntries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.no_log_entries),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumnScrollbar(
                state = listState,
                settings = ScrollbarDefaults.defaultScrollbarSettings(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp),
                    reverseLayout = true,
                ) {
                    items(filteredEntries) { logEntry ->
                        LogEntryItem(logEntry = logEntry)
                    }
                }
            }
        }
    }
}

/** Which session's log, warnings and errors only, and which config. */
@Composable
private fun LogFilters(
    sessionFiles: List<File>,
    selectedFile: File?,
    onSession: (File?) -> Unit,
    problemsOnly: Boolean,
    onProblemsOnly: (Boolean) -> Unit,
    configs: List<String>,
    config: String?,
    onConfig: (String?) -> Unit,
) {
    var sessionMenu by remember { mutableStateOf(false) }
    var configMenu by remember { mutableStateOf(false) }
    val current = stringResource(R.string.log_session_current)
    fun sessionLabel(file: File?) =
        file?.let { f -> LogFileManager.startedAt(f)?.let(::formatSessionStart) ?: f.name }
            ?: current

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            FilterChip(
                selected = selectedFile != null,
                onClick = { sessionMenu = true },
                label = { Text(sessionLabel(selectedFile)) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            )
            DropdownMenu(expanded = sessionMenu, onDismissRequest = { sessionMenu = false }) {
                sessionFiles.forEachIndexed { i, f ->
                    DropdownMenuItem(
                        text = { Text(if (i == 0) current else sessionLabel(f)) },
                        onClick = {
                            sessionMenu = false
                            onSession(if (i == 0) null else f)
                        },
                    )
                }
            }
        }
        FilterChip(
            selected = problemsOnly,
            onClick = { onProblemsOnly(!problemsOnly) },
            label = { Text(stringResource(R.string.log_filter_problems)) },
        )
        if (configs.size > 1 || config != null) {
            Box {
                FilterChip(
                    selected = config != null,
                    onClick = { configMenu = true },
                    label = { Text(config ?: stringResource(R.string.log_filter_all_configs)) },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                )
                DropdownMenu(expanded = configMenu, onDismissRequest = { configMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.log_filter_all_configs)) },
                        onClick = {
                            configMenu = false
                            onConfig(null)
                        },
                    )
                    configs.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c) },
                            onClick = {
                                configMenu = false
                                onConfig(c)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun formatSessionStart(t: Long): String =
    android.text.format.DateFormat.format(
            android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMdHm"),
            t,
        )
        .toString()

private val LOG_TIME = Regex("""^\d\d:\d\d:\d\d\.\d{3}""")

@Composable
fun LogEntryItem(logEntry: String) {
    val colorOnSurface = MaterialTheme.colorScheme.onSurface
    val timestampColor = MaterialTheme.colorScheme.primary
    val color =
        when {
            logEntry.contains("[Error]") -> MaterialTheme.colorScheme.error
            logEntry.contains("[Warning]") -> MaterialTheme.colorScheme.tertiary
            else -> colorOnSurface
        }

    val annotatedString =
        remember(logEntry, color) {
            buildAnnotatedString {
                val time = LOG_TIME.find(logEntry)?.value
                if (time != null) {
                    withStyle(style = SpanStyle(color = timestampColor)) { append(time) }
                    append(logEntry.substring(time.length))
                } else {
                    append(logEntry)
                }
            }
        }

    Text(
        text = annotatedString,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
