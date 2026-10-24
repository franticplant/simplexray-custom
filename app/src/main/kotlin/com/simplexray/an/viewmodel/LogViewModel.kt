// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.simplexray.an.data.source.LogFileManager
import com.simplexray.an.service.TProxyService
import java.io.File
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "LogViewModel"

@OptIn(FlowPreview::class)
class LogViewModel(application: Application) : AndroidViewModel(application) {

    private val logFileManager = LogFileManager(application)

    private val _logEntries = MutableStateFlow<List<String>>(emptyList())
    val logEntries: StateFlow<List<String>> = _logEntries.asStateFlow()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filteredEntries = MutableStateFlow<List<String>>(emptyList())
    val filteredEntries: StateFlow<List<String>> = _filteredEntries.asStateFlow()

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    /** Show only warnings and errors. */
    private val _problemsOnly = MutableStateFlow(false)
    val problemsOnly: StateFlow<Boolean> = _problemsOnly.asStateFlow()

    /** Show only this config's lines; null for all. */
    private val _configFilter = MutableStateFlow<String?>(null)
    val configFilter: StateFlow<String?> = _configFilter.asStateFlow()

    /** The configs the shown log has lines from. */
    private val _configs = MutableStateFlow<List<String>>(emptyList())
    val configs: StateFlow<List<String>> = _configs.asStateFlow()

    /** Session logs, newest first, and the one shown (null: the current one, followed live). */
    private val _sessionFiles = MutableStateFlow<List<File>>(emptyList())
    val sessionFiles: StateFlow<List<File>> = _sessionFiles.asStateFlow()
    private val _selectedFile = MutableStateFlow<File?>(null)
    val selectedFile: StateFlow<File?> = _selectedFile.asStateFlow()

    fun setProblemsOnly(value: Boolean) {
        _problemsOnly.value = value
    }

    fun setConfigFilter(config: String?) {
        _configFilter.value = config
    }

    /** Shows a past session's log, or the current one (null). */
    fun selectSession(file: File?) {
        _selectedFile.value = file?.takeIf { it != _sessionFiles.value.firstOrNull() }
        loadLogs()
    }

    private val _hasLogsToExport = MutableStateFlow(false)
    val hasLogsToExport: StateFlow<Boolean> = _hasLogsToExport.asStateFlow()

    private val logEntrySet: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val logMutex = Mutex()

    private var logUpdateReceiver: BroadcastReceiver

    init {
        Log.d(TAG, "LogViewModel initialized.")
        logUpdateReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (TProxyService.ACTION_LOG_UPDATE == intent.action) {
                        val newLogs = intent.getStringArrayListExtra(TProxyService.EXTRA_LOG_DATA)
                        // A past session's log does not change.
                        if (!newLogs.isNullOrEmpty() && _selectedFile.value == null) {
                            Log.d(
                                TAG,
                                "Received log update broadcast with ${newLogs.size} entries.",
                            )
                            viewModelScope.launch {
                                processNewLogs(newLogs)
                            }
                        } else {
                            Log.w(
                                TAG,
                                "Received log update broadcast, but log data list is null or empty.",
                            )
                        }
                    }
                }
            }
        viewModelScope.launch {
            logEntries.collect { entries ->
                _hasLogsToExport.value = entries.isNotEmpty() && logFileManager.logFile.exists()
            }
        }
        viewModelScope.launch {
            combine(
                    logEntries,
                    searchQuery.debounce(200),
                    problemsOnly,
                    configFilter,
                ) { logs, query, problems, config ->
                    logs.filter {
                        (query.isBlank() || it.contains(query, ignoreCase = true)) &&
                            (!problems || LogFileManager.isProblem(it)) &&
                            (config == null || LogFileManager.configOf(it) == config)
                    }
                }
                .flowOn(Dispatchers.Default)
                .collect { _filteredEntries.value = it }
        }
    }

    fun registerLogReceiver(context: Context) {
        val filter = IntentFilter(TProxyService.ACTION_LOG_UPDATE)
        ContextCompat.registerReceiver(
            context,
            logUpdateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        Log.d(TAG, "Log receiver registered.")
    }

    fun unregisterLogReceiver(context: Context) {
        context.unregisterReceiver(logUpdateReceiver)
        Log.d(TAG, "Log receiver unregistered.")
    }

    fun loadLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Loading logs.")
            _sessionFiles.value = logFileManager.sessionFiles()
            val savedLogData = logFileManager.readLogs(getLogFile())
            val initialLogs =
                if (!savedLogData.isNullOrEmpty()) {
                    savedLogData.split("\n").filter { it.trim().isNotEmpty() }
                } else {
                    emptyList()
                }
            processInitialLogs(initialLogs)
        }
    }

    private suspend fun processInitialLogs(initialLogs: List<String>) {
        logMutex.withLock {
            logEntrySet.clear()
            _logEntries.value = initialLogs.filter { logEntrySet.add(it) }.reversed()
        }
        _configs.value = initialLogs.mapNotNull(LogFileManager::configOf).distinct().sorted()
        Log.d(TAG, "Processed initial logs: ${_logEntries.value.size} unique entries.")
    }

    private suspend fun processNewLogs(newLogs: ArrayList<String>) {
        val uniqueNewLogs = logMutex.withLock {
            newLogs.filter { it.trim().isNotEmpty() && logEntrySet.add(it) }
        }
        if (uniqueNewLogs.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                _logEntries.value = uniqueNewLogs + _logEntries.value
                val seen = uniqueNewLogs.mapNotNull(LogFileManager::configOf)
                if (!_configs.value.containsAll(seen)) {
                    _configs.value = (_configs.value + seen).distinct().sorted()
                }
            }
            Log.d(TAG, "Added ${uniqueNewLogs.size} new unique log entries.")
        } else {
            Log.d(TAG, "No unique log entries from broadcast to add.")
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            logMutex.withLock {
                _logEntries.value = emptyList()
                logEntrySet.clear()
            }
            _selectedFile.value = null
            _configs.value = emptyList()
            _configFilter.value = null
            Log.d(TAG, "Logs cleared.")
        }
    }

    /** The log shown: a past session's, or the current one. */
    fun getLogFile(): File = _selectedFile.value ?: logFileManager.logFile
}

class LogViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LogViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LogViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
