// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.viewmodel

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.simplexray.an.BuildConfig
import com.simplexray.an.R
import com.simplexray.an.common.CoreStatsClient
import com.simplexray.an.common.ROUTE_ACTIVITY
import com.simplexray.an.common.ROUTE_APP_LIST
import com.simplexray.an.common.ROUTE_CONFIG_EDIT
import com.simplexray.an.common.ThemeMode
import com.simplexray.an.core.InstanceRegistry
import com.simplexray.an.core.InstanceStatus
import com.simplexray.an.core.XrayInstanceInfo
import com.simplexray.an.data.source.FileManager
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.service.TProxyService
import com.simplexray.an.telemetry.Category
import com.simplexray.an.telemetry.Level
import com.simplexray.an.telemetry.Telemetry
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.regex.Pattern
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val TAG = "MainViewModel"

sealed class MainViewUiEvent {
    data class ShowSnackbar(val message: String) : MainViewUiEvent()

    data class ShareLauncher(val intent: Intent) : MainViewUiEvent()

    data class StartService(val intent: Intent) : MainViewUiEvent()

    data object RefreshConfigList : MainViewUiEvent()

    data class Navigate(val route: String) : MainViewUiEvent()

    /** Connect the way the main switch does: permissions first, then the VPN or the core alone. */
    data object Connect : MainViewUiEvent()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val prefs: Preferences = Preferences(application)
    private val activityScope: CoroutineScope = viewModelScope
    private var compressedBackupData: ByteArray? = null

    // Per running instance, keyed by its API "address:port" (new on every restart, so a restart
    // starts its counters afresh): a stats client and the last traffic reading. Guarded by
    // statsLock.
    private val statsClients = mutableMapOf<String, CoreStatsClient>()
    private val lastTraffic = mutableMapOf<String, Pair<TrafficState, Long>>()
    private val statsLock = Any()

    private val fileManager: FileManager = FileManager(application, prefs)

    var reloadView: (() -> Unit)? = null

    lateinit var appListViewModel: AppListViewModel
    lateinit var configEditViewModel: ConfigEditViewModel

    private val _settingsState =
        MutableStateFlow(
            SettingsState(
                socksAddress = InputFieldState(prefs.socksAddress),
                socksPort = InputFieldState(prefs.socksPort.toString()),
                socksUser = InputFieldState(prefs.socksUsername),
                socksPass = InputFieldState(prefs.socksPassword),
                dnsIpv4 = InputFieldState(prefs.dnsIpv4),
                dnsIpv6 = InputFieldState(prefs.dnsIpv6),
                switches =
                    SwitchStates(
                        ipv6Enabled = prefs.ipv6,
                        useTemplateEnabled = prefs.useTemplate,
                        httpProxyEnabled = prefs.httpProxyEnabled,
                        bypassLanEnabled = prefs.bypassLan,
                        disableVpn = prefs.disableVpn,
                        themeMode = prefs.theme,
                    ),
                info =
                    InfoStates(
                        appVersion = BuildConfig.VERSION_NAME,
                        kernelVersion = "N/A",
                        geoipSummary = "",
                        geositeSummary = "",
                        geoipUrl = prefs.geoipUrl,
                        geositeUrl = prefs.geositeUrl,
                    ),
                files =
                    FileStates(
                        isGeoipCustom = prefs.customGeoipImported,
                        isGeositeCustom = prefs.customGeositeImported,
                    ),
                connectivityTestTarget = InputFieldState(prefs.connectivityTestTarget),
                connectivityTestTimeout = InputFieldState(prefs.connectivityTestTimeout.toString()),
            )
        )
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    private val _coreStatsState = MutableStateFlow(CoreStatsState())
    val coreStatsState: StateFlow<CoreStatsState> = _coreStatsState.asStateFlow()

    // The last THROUGHPUT_SAMPLES (upload, download) rates, one per poll, for the live chart.
    private val _throughput = MutableStateFlow<List<Pair<Long, Long>>>(emptyList())
    val throughput: StateFlow<List<Pair<Long, Long>>> = _throughput.asStateFlow()

    private val _activeInstances = MutableStateFlow<List<InstanceState>>(emptyList())
    val activeInstances: StateFlow<List<InstanceState>> = _activeInstances.asStateFlow()

    private val _controlMenuClickable = MutableStateFlow(true)
    val controlMenuClickable: StateFlow<Boolean> = _controlMenuClickable.asStateFlow()

    private val _isServiceEnabled = MutableStateFlow(false)
    val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()

    private val _uiEvent = Channel<MainViewUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    private val _configFiles = MutableStateFlow<List<File>>(emptyList())
    val configFiles: StateFlow<List<File>> = _configFiles.asStateFlow()

    private val _selectedConfigFile = MutableStateFlow<File?>(null)
    val selectedConfigFile: StateFlow<File?> = _selectedConfigFile.asStateFlow()

    private val _geoipDownloadProgress = MutableStateFlow<String?>(null)
    val geoipDownloadProgress: StateFlow<String?> = _geoipDownloadProgress.asStateFlow()
    private var geoipDownloadJob: Job? = null

    private val _geositeDownloadProgress = MutableStateFlow<String?>(null)
    val geositeDownloadProgress: StateFlow<String?> = _geositeDownloadProgress.asStateFlow()
    private var geositeDownloadJob: Job? = null

    private val _isCheckingForUpdates = MutableStateFlow(false)
    val isCheckingForUpdates: StateFlow<Boolean> = _isCheckingForUpdates.asStateFlow()

    private val _newVersionAvailable = MutableStateFlow<String?>(null)
    val newVersionAvailable: StateFlow<String?> = _newVersionAvailable.asStateFlow()

    private val startReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.d(TAG, "Service started")
                setServiceEnabled(true)
                setControlMenuClickable(true)
            }
        }

    private val stopReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.d(TAG, "Service stopped")
                setServiceEnabled(false)
                setControlMenuClickable(true)
                _coreStatsState.value = CoreStatsState()
                _throughput.value = emptyList()
                _activeInstances.value = emptyList()
                closeStatsClients()
            }
        }

    init {
        Log.d(TAG, "MainViewModel initialized.")

        setupGlobalSocksAuthenticator()

        viewModelScope.launch(Dispatchers.IO) {
            _isServiceEnabled.value = isServiceRunning(application, TProxyService::class.java)

            updateSettingsState()
            loadKernelVersion()
            refreshConfigFileList()
        }
    }

    private fun updateSettingsState() {
        _settingsState.value =
            _settingsState.value.copy(
                socksAddress = InputFieldState(prefs.socksAddress),
                socksPort = InputFieldState(prefs.socksPort.toString()),
                socksUser = InputFieldState(prefs.socksUsername),
                socksPass = InputFieldState(prefs.socksPassword),
                dnsIpv4 = InputFieldState(prefs.dnsIpv4),
                dnsIpv6 = InputFieldState(prefs.dnsIpv6),
                switches =
                    SwitchStates(
                        ipv6Enabled = prefs.ipv6,
                        useTemplateEnabled = prefs.useTemplate,
                        httpProxyEnabled = prefs.httpProxyEnabled,
                        bypassLanEnabled = prefs.bypassLan,
                        disableVpn = prefs.disableVpn,
                        themeMode = prefs.theme,
                    ),
                info =
                    _settingsState.value.info.copy(
                        appVersion = BuildConfig.VERSION_NAME,
                        geoipSummary = fileManager.getRuleFileSummary("geoip.dat"),
                        geositeSummary = fileManager.getRuleFileSummary("geosite.dat"),
                        geoipUrl = prefs.geoipUrl,
                        geositeUrl = prefs.geositeUrl,
                    ),
                files =
                    FileStates(
                        isGeoipCustom = prefs.customGeoipImported,
                        isGeositeCustom = prefs.customGeositeImported,
                    ),
                connectivityTestTarget = InputFieldState(prefs.connectivityTestTarget),
                connectivityTestTimeout = InputFieldState(prefs.connectivityTestTimeout.toString()),
            )
    }

    private fun loadKernelVersion() {
        val libraryDir = TProxyService.getNativeLibraryDir(application)
        val xrayPath = "$libraryDir/libxray.so"
        try {
            val process = Runtime.getRuntime().exec("$xrayPath -version")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val firstLine = reader.readLine()
            process.destroy()
            _settingsState.value =
                _settingsState.value.copy(
                    info = _settingsState.value.info.copy(kernelVersion = firstLine ?: "N/A")
                )
        } catch (e: IOException) {
            Log.e(TAG, "Failed to get xray version", e)
            _settingsState.value =
                _settingsState.value.copy(
                    info = _settingsState.value.info.copy(kernelVersion = "N/A")
                )
        }
    }

    private fun setupGlobalSocksAuthenticator() {
        java.net.Authenticator.setDefault(
            object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): java.net.PasswordAuthentication? {
                    val user = prefs.socksUsername
                    val pass = prefs.socksPassword

                    return if (user.isNotEmpty() || pass.isNotEmpty()) {
                        java.net.PasswordAuthentication(user, pass.toCharArray())
                    } else {
                        null
                    }
                }
            }
        )
    }

    fun setControlMenuClickable(isClickable: Boolean) {
        _controlMenuClickable.value = isClickable
    }

    fun setServiceEnabled(enabled: Boolean) {
        _isServiceEnabled.value = enabled
        prefs.enable = enabled
    }

    fun clearCompressedBackupData() {
        compressedBackupData = null
    }

    fun performBackup(createFileLauncher: ActivityResultLauncher<String>) {
        activityScope.launch {
            compressedBackupData = fileManager.compressBackupData()
            val filename = "simplexray_backup_" + System.currentTimeMillis() + ".dat"
            withContext(Dispatchers.Main) {
                createFileLauncher.launch(filename)
            }
        }
    }

    suspend fun handleBackupFileCreationResult(uri: Uri) {
        withContext(Dispatchers.IO) {
            if (compressedBackupData != null) {
                val dataToWrite: ByteArray = compressedBackupData as ByteArray
                compressedBackupData = null
                try {
                    application.contentResolver.openOutputStream(uri).use { os ->
                        if (os != null) {
                            os.write(dataToWrite)
                            Log.d(TAG, "Backup successful to: $uri")
                            _uiEvent.trySend(
                                MainViewUiEvent.ShowSnackbar(
                                    application.getString(R.string.backup_success)
                                )
                            )
                        } else {
                            Log.e(TAG, "Failed to open output stream for backup URI: $uri")
                            _uiEvent.trySend(
                                MainViewUiEvent.ShowSnackbar(
                                    application.getString(R.string.backup_failed)
                                )
                            )
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Error writing backup data to URI: $uri", e)
                    _uiEvent.trySend(
                        MainViewUiEvent.ShowSnackbar(application.getString(R.string.backup_failed))
                    )
                }
            } else {
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(application.getString(R.string.backup_failed))
                )
                Log.e(TAG, "Compressed backup data is null in launcher callback.")
            }
        }
    }

    suspend fun startRestoreTask(uri: Uri) {
        withContext(Dispatchers.IO) {
            val success = fileManager.decompressAndRestore(uri)
            if (success) {
                updateSettingsState()
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(application.getString(R.string.restore_success))
                )
                Log.d(TAG, "Restore successful.")
                refreshConfigFileList()
            } else {
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(application.getString(R.string.restore_failed))
                )
            }
        }
    }

    suspend fun createConfigFile(): String? {
        val filePath = fileManager.createConfigFile(application.assets)
        if (filePath == null) {
            _uiEvent.trySend(
                MainViewUiEvent.ShowSnackbar(application.getString(R.string.create_config_failed))
            )
        } else {
            refreshConfigFileList()
        }
        return filePath
    }

    private fun apiKey(info: XrayInstanceInfo) = "${info.apiAddress}:${info.apiPort}"

    private fun statsClientFor(info: XrayInstanceInfo): CoreStatsClient =
        synchronized(statsLock) {
            statsClients.getOrPut(apiKey(info)) {
                CoreStatsClient.create(info.apiAddress, info.apiPort)
            }
        }

    private fun closeStatsClients(keep: Set<String> = emptySet()) {
        val stale =
            synchronized(statsLock) {
                lastTraffic.keys.retainAll(keep)
                val keys = statsClients.keys - keep
                keys.mapNotNull { statsClients.remove(it) }
            }
        stale.forEach { it.close() }
    }

    /** The instance's stats, or null if its API does not answer. */
    private suspend fun fetchStats(info: XrayInstanceInfo): InstanceStats? {
        val client = statsClientFor(info)
        val sys = client.getSystemStats() ?: return null
        val traffic = client.getTraffic() ?: TrafficState(0, 0)
        val now = SystemClock.elapsedRealtime()
        val previous = synchronized(statsLock) { lastTraffic.put(apiKey(info), traffic to now) }
        val (before, then) = previous ?: (traffic to now)
        return InstanceStats(
            uplink = traffic.uplink,
            downlink = traffic.downlink,
            uplinkRate = ratePerSecond(before.uplink, traffic.uplink, now - then),
            downlinkRate = ratePerSecond(before.downlink, traffic.downlink, now - then),
            numGoroutine = sys.numGoroutine,
            alloc = sys.alloc,
            uptime = sys.uptime,
        )
    }

    /**
     * Reads the instances the service process supervises from InstanceRegistry and fetches the
     * stats of the running ones over their gRPC APIs, all at once.
     */
    private suspend fun refreshInstances(): List<InstanceState> = coroutineScope {
        val registered =
            if (_isServiceEnabled.value) InstanceRegistry.read(application) else emptyList()
        val running = registered.filter { it.status == InstanceStatus.RUNNING }
        closeStatsClients(keep = running.map(::apiKey).toSet())

        val states =
            registered
                .map { info ->
                    async {
                        val stats =
                            if (info.status == InstanceStatus.RUNNING) fetchStats(info) else null
                        InstanceState(info, stats)
                    }
                }
                .awaitAll()
        _activeInstances.value = states
        states
    }

    /** Polls every instance once; the screens showing stats call it every second. */
    suspend fun updateCoreStats() {
        if (!_isServiceEnabled.value) return
        val stats = CoreStatsState.of(refreshInstances())
        _coreStatsState.value = stats
        _throughput.value =
            (_throughput.value + (stats.uplinkRate to stats.downlinkRate)).takeLast(
                THROUGHPUT_SAMPLES
            )
    }

    fun refreshActiveInstances() {
        viewModelScope.launch(Dispatchers.IO) { refreshInstances() }
    }

    fun toggleInstance(configPath: String) {
        viewModelScope.launch {
            val isActive =
                _activeInstances.value.any { it.info.configPath == configPath && it.info.isActive }
            if (isActive) {
                stopInstanceConfig(configPath)
            } else {
                startInstanceConfig(configPath)
            }
        }
    }

    fun startInstanceConfig(configPath: String) {
        viewModelScope.launch {
            prefs.setConfigActive(configPath, true)
            if (_isServiceEnabled.value) {
                val intent =
                    Intent(application, TProxyService::class.java)
                        .setAction(TProxyService.ACTION_START_INSTANCE)
                        .putExtra(TProxyService.EXTRA_CONFIG_PATH, configPath)
                _uiEvent.trySend(MainViewUiEvent.StartService(intent))
            } else {
                updateSelectedConfigFile(File(configPath))
                _uiEvent.trySend(MainViewUiEvent.Connect)
            }
            refreshActiveInstances()
        }
    }

    fun stopInstanceConfig(configPath: String) {
        viewModelScope.launch {
            prefs.setConfigActive(configPath, false)
            val carriesVpn =
                _activeInstances.value.any {
                    it.info.configPath == configPath && it.info.isPrimaryVpn
                }
            if (carriesVpn) {
                // Without it the VPN has nowhere to send traffic, so turn the VPN off too.
                stopTProxyService()
                return@launch
            }
            val intent =
                Intent(application, TProxyService::class.java)
                    .setAction(TProxyService.ACTION_STOP_INSTANCE)
                    .putExtra(TProxyService.EXTRA_CONFIG_PATH, configPath)
            _uiEvent.trySend(MainViewUiEvent.StartService(intent))
            refreshActiveInstances()
        }
    }

    suspend fun importConfigFromClipboard(): String? {
        val filePath = fileManager.importConfigFromClipboard()
        if (filePath == null) {
            _uiEvent.trySend(
                MainViewUiEvent.ShowSnackbar(application.getString(R.string.import_failed))
            )
        } else {
            refreshConfigFileList()
        }
        return filePath
    }

    suspend fun handleSharedContent(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!fileManager.importConfigFromContent(content).isNullOrEmpty()) {
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(application.getString(R.string.import_success))
                )
                refreshConfigFileList()
            } else {
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(
                        application.getString(R.string.invalid_config_format)
                    )
                )
            }
        }
    }

    suspend fun deleteConfigFile(file: File, callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (
                _isServiceEnabled.value &&
                    _selectedConfigFile.value != null &&
                    _selectedConfigFile.value == file
            ) {
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(application.getString(R.string.config_in_use))
                )
                Log.w(TAG, "Attempted to delete selected config file: ${file.name}")
                return@launch
            }

            val success = fileManager.deleteConfigFile(file)
            if (success) {
                withContext(Dispatchers.Main) {
                    refreshConfigFileList()
                }
            } else {
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(application.getString(R.string.delete_fail))
                )
            }
            callback()
        }
    }

    fun extractAssetsIfNeeded() {
        fileManager.extractAssetsIfNeeded()
    }

    fun updateSocksAddress(addressString: String): Boolean {
        val matcherIpv4 = IPV4_PATTERN.matcher(addressString)
        val matcherIpv6 = IPV6_PATTERN.matcher(addressString)
        return if (matcherIpv4.matches()) {
            prefs.socksAddress = addressString
            _settingsState.value =
                _settingsState.value.copy(socksAddress = InputFieldState(addressString))
            true
        } else if (matcherIpv6.matches()) {
            prefs.socksAddress = addressString
            _settingsState.value =
                _settingsState.value.copy(socksAddress = InputFieldState(addressString))
            true
        } else {
            _settingsState.value =
                _settingsState.value.copy(
                    socksAddress =
                        InputFieldState(
                            value = addressString,
                            error = application.getString(R.string.invalid_ipv4_or_ipv6),
                            isValid = false,
                        )
                )
            false
        }
    }

    fun updateSocksPort(portString: String): Boolean {
        return try {
            val port = portString.toInt()
            if (port in 1025..65535) {
                prefs.socksPort = port
                _settingsState.value =
                    _settingsState.value.copy(socksPort = InputFieldState(portString))
                true
            } else {
                _settingsState.value =
                    _settingsState.value.copy(
                        socksPort =
                            InputFieldState(
                                value = portString,
                                error = application.getString(R.string.invalid_port_range),
                                isValid = false,
                            )
                    )
                false
            }
        } catch (e: NumberFormatException) {
            _settingsState.value =
                _settingsState.value.copy(
                    socksPort =
                        InputFieldState(
                            value = portString,
                            error = application.getString(R.string.invalid_port),
                            isValid = false,
                        )
                )
            false
        }
    }

    fun updateSocksUser(userString: String): Boolean {
        val byteCount = userString.toByteArray(Charsets.UTF_8).size
        return if (byteCount <= 255) {
            prefs.socksUsername = userString
            _settingsState.value =
                _settingsState.value.copy(socksUser = InputFieldState(userString))
            true
        } else {
            _settingsState.value =
                _settingsState.value.copy(
                    socksUser =
                        InputFieldState(
                            value = userString,
                            error = "Username length must not exceed 255 bytes",
                            isValid = false,
                        )
                )
            false
        }
    }

    fun updateSocksPass(passString: String): Boolean {
        val byteCount = passString.toByteArray(Charsets.UTF_8).size
        return if (byteCount <= 255) {
            prefs.socksPassword = passString
            _settingsState.value =
                _settingsState.value.copy(socksPass = InputFieldState(passString))
            true
        } else {
            _settingsState.value =
                _settingsState.value.copy(
                    socksPass =
                        InputFieldState(
                            value = passString,
                            error = "Password length must not exceed 255 bytes",
                            isValid = false,
                        )
                )
            false
        }
    }

    fun updateDnsIpv4(ipv4Addr: String): Boolean {
        val matcher = IPV4_PATTERN.matcher(ipv4Addr)
        return if (matcher.matches()) {
            prefs.dnsIpv4 = ipv4Addr
            _settingsState.value = _settingsState.value.copy(dnsIpv4 = InputFieldState(ipv4Addr))
            true
        } else {
            _settingsState.value =
                _settingsState.value.copy(
                    dnsIpv4 =
                        InputFieldState(
                            value = ipv4Addr,
                            error = application.getString(R.string.invalid_ipv4),
                            isValid = false,
                        )
                )
            false
        }
    }

    fun updateDnsIpv6(ipv6Addr: String): Boolean {
        val matcher = IPV6_PATTERN.matcher(ipv6Addr)
        return if (matcher.matches()) {
            prefs.dnsIpv6 = ipv6Addr
            _settingsState.value = _settingsState.value.copy(dnsIpv6 = InputFieldState(ipv6Addr))
            true
        } else {
            _settingsState.value =
                _settingsState.value.copy(
                    dnsIpv6 =
                        InputFieldState(
                            value = ipv6Addr,
                            error = application.getString(R.string.invalid_ipv6),
                            isValid = false,
                        )
                )
            false
        }
    }

    fun setIpv6Enabled(enabled: Boolean) {
        prefs.ipv6 = enabled
        _settingsState.value =
            _settingsState.value.copy(
                switches = _settingsState.value.switches.copy(ipv6Enabled = enabled)
            )
    }

    fun setUseTemplateEnabled(enabled: Boolean) {
        prefs.useTemplate = enabled
        _settingsState.value =
            _settingsState.value.copy(
                switches = _settingsState.value.switches.copy(useTemplateEnabled = enabled)
            )
    }

    fun setHttpProxyEnabled(enabled: Boolean) {
        prefs.httpProxyEnabled = enabled
        _settingsState.value =
            _settingsState.value.copy(
                switches = _settingsState.value.switches.copy(httpProxyEnabled = enabled)
            )
    }

    fun setBypassLanEnabled(enabled: Boolean) {
        prefs.bypassLan = enabled
        _settingsState.value =
            _settingsState.value.copy(
                switches = _settingsState.value.switches.copy(bypassLanEnabled = enabled)
            )
    }

    fun setDisableVpnEnabled(enabled: Boolean) {
        prefs.disableVpn = enabled
        _settingsState.value =
            _settingsState.value.copy(
                switches = _settingsState.value.switches.copy(disableVpn = enabled)
            )
    }

    fun setTheme(mode: ThemeMode) {
        prefs.theme = mode
        _settingsState.value =
            _settingsState.value.copy(
                switches = _settingsState.value.switches.copy(themeMode = mode)
            )
        reloadView?.invoke()
    }

    fun importRuleFile(uri: Uri, fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = fileManager.importRuleFile(uri, fileName)
            if (success) {
                when (fileName) {
                    "geoip.dat" -> {
                        _settingsState.value =
                            _settingsState.value.copy(
                                files =
                                    _settingsState.value.files.copy(
                                        isGeoipCustom = prefs.customGeoipImported
                                    ),
                                info =
                                    _settingsState.value.info.copy(
                                        geoipSummary = fileManager.getRuleFileSummary("geoip.dat")
                                    ),
                            )
                    }

                    "geosite.dat" -> {
                        _settingsState.value =
                            _settingsState.value.copy(
                                files =
                                    _settingsState.value.files.copy(
                                        isGeositeCustom = prefs.customGeositeImported
                                    ),
                                info =
                                    _settingsState.value.info.copy(
                                        geositeSummary =
                                            fileManager.getRuleFileSummary("geosite.dat")
                                    ),
                            )
                    }
                }
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(
                        "$fileName ${application.getString(R.string.import_success)}"
                    )
                )
            } else {
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(application.getString(R.string.import_failed))
                )
            }
        }
    }

    fun showExportFailedSnackbar() {
        _uiEvent.trySend(
            MainViewUiEvent.ShowSnackbar(application.getString(R.string.export_failed))
        )
    }

    fun startTProxyService(action: String) {
        viewModelScope.launch {
            if (_selectedConfigFile.value == null) {
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(application.getString(R.string.not_select_config))
                )
                Log.w(TAG, "Cannot start service: no config file selected.")
                setControlMenuClickable(true)
                return@launch
            }
            val intent = Intent(application, TProxyService::class.java).setAction(action)
            _uiEvent.trySend(MainViewUiEvent.StartService(intent))
        }
    }

    fun editConfig(filePath: String) {
        viewModelScope.launch {
            configEditViewModel = ConfigEditViewModel(application, filePath, prefs)
            _uiEvent.trySend(MainViewUiEvent.Navigate(ROUTE_CONFIG_EDIT))
        }
    }

    fun shareIntent(chooserIntent: Intent, packageManager: PackageManager) {
        viewModelScope.launch {
            if (chooserIntent.resolveActivity(packageManager) != null) {
                _uiEvent.trySend(MainViewUiEvent.ShareLauncher(chooserIntent))
                Log.d(TAG, "Export intent resolved and started.")
            } else {
                Log.w(TAG, "No activity found to handle export intent.")
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(application.getString(R.string.no_app_for_export))
                )
            }
        }
    }

    fun stopTProxyService() {
        viewModelScope.launch {
            val intent =
                Intent(
                        application,
                        TProxyService::class.java,
                    )
                    .setAction(TProxyService.ACTION_DISCONNECT)
            _uiEvent.trySend(MainViewUiEvent.StartService(intent))
        }
    }

    /**
     * Runtime permissions the connection works better with and the user hasn't granted. Without
     * POST_NOTIFICATIONS, the running service's notification is hidden.
     */
    fun missingRuntimePermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
        .filter {
            application.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        .toTypedArray()

    /** Starts the core alone or with the VPN, as the settings say. */
    fun connect(vpnPrepareLauncher: ActivityResultLauncher<Intent>) {
        if (settingsState.value.switches.disableVpn) {
            startTProxyService(TProxyService.ACTION_START)
        } else {
            prepareAndStartVpn(vpnPrepareLauncher)
        }
    }

    fun prepareAndStartVpn(vpnPrepareLauncher: ActivityResultLauncher<Intent>) {
        viewModelScope.launch {
            if (_selectedConfigFile.value == null) {
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(application.getString(R.string.not_select_config))
                )
                Log.w(TAG, "Cannot prepare VPN: no config file selected.")
                setControlMenuClickable(true)
                return@launch
            }
            val vpnIntent = VpnService.prepare(application)
            if (vpnIntent != null) {
                vpnPrepareLauncher.launch(vpnIntent)
            } else {
                startTProxyService(TProxyService.ACTION_CONNECT)
            }
        }
    }

    fun openActivity() {
        _uiEvent.trySend(MainViewUiEvent.Navigate(ROUTE_ACTIVITY))
    }

    fun navigateToAppList() {
        viewModelScope.launch {
            appListViewModel = AppListViewModel(application)
            _uiEvent.trySend(MainViewUiEvent.Navigate(ROUTE_APP_LIST))
        }
    }

    fun moveConfigFile(fromIndex: Int, toIndex: Int) {
        val currentList = _configFiles.value.toMutableList()
        val movedItem = currentList.removeAt(fromIndex)
        currentList.add(toIndex, movedItem)
        _configFiles.value = currentList
        prefs.configFilesOrder = currentList.map { it.name }
    }

    fun refreshConfigFileList() {
        viewModelScope.launch(Dispatchers.IO) {
            InstanceRegistry.removeLegacyFile(application)
            val filesDir = application.filesDir
            val actualFiles =
                filesDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.toList()
                    ?: emptyList()
            val actualFilesByName = actualFiles.associateBy { it.name }
            val savedOrder = prefs.configFilesOrder

            val newOrder = mutableListOf<File>()
            val remainingActualFileNames = actualFilesByName.toMutableMap()

            savedOrder.forEach { filename ->
                actualFilesByName[filename]?.let { file ->
                    newOrder.add(file)
                    remainingActualFileNames.remove(filename)
                }
            }

            newOrder.addAll(remainingActualFileNames.values.filter { it !in newOrder })

            _configFiles.value = newOrder
            prefs.configFilesOrder = newOrder.map { it.name }

            val currentSelectedPath = prefs.selectedConfigPath
            var fileToSelect: File? = null

            if (currentSelectedPath != null) {
                val foundSelected = newOrder.find { it.absolutePath == currentSelectedPath }
                if (foundSelected != null) {
                    fileToSelect = foundSelected
                }
            }

            if (fileToSelect == null) {
                fileToSelect = newOrder.firstOrNull()
            }

            _selectedConfigFile.value = fileToSelect
            prefs.selectedConfigPath = fileToSelect?.absolutePath
        }
    }

    fun updateSelectedConfigFile(file: File?) {
        _selectedConfigFile.value = file
        prefs.selectedConfigPath = file?.absolutePath
    }

    fun updateConnectivityTestTarget(target: String) {
        val isValid =
            try {
                val url = URL(target)
                url.protocol == "http" || url.protocol == "https"
            } catch (e: Exception) {
                false
            }
        if (isValid) {
            prefs.connectivityTestTarget = target
            _settingsState.value =
                _settingsState.value.copy(connectivityTestTarget = InputFieldState(target))
        } else {
            _settingsState.value =
                _settingsState.value.copy(
                    connectivityTestTarget =
                        InputFieldState(
                            value = target,
                            error = application.getString(R.string.connectivity_test_invalid_url),
                            isValid = false,
                        )
                )
        }
    }

    fun updateConnectivityTestTimeout(timeout: String) {
        val timeoutInt = timeout.toIntOrNull()
        if (timeoutInt != null && timeoutInt > 0) {
            prefs.connectivityTestTimeout = timeoutInt
            _settingsState.value =
                _settingsState.value.copy(connectivityTestTimeout = InputFieldState(timeout))
        } else {
            _settingsState.value =
                _settingsState.value.copy(
                    connectivityTestTimeout =
                        InputFieldState(
                            value = timeout,
                            error = application.getString(R.string.invalid_timeout),
                            isValid = false,
                        )
                )
        }
    }

    fun testConnectivity() {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = prefs
            val url: URL
            try {
                url = URL(prefs.connectivityTestTarget)
            } catch (e: Exception) {
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(
                        application.getString(R.string.connectivity_test_invalid_url)
                    )
                )
                return@launch
            }
            val host = url.host
            val port = if (url.port > 0) url.port else url.defaultPort
            val path = if (url.path.isNullOrEmpty()) "/" else url.path
            val isHttps = url.protocol == "https"
            val proxy =
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(prefs.socksAddress, prefs.socksPort))
            val timeout = prefs.connectivityTestTimeout
            val start = System.currentTimeMillis()
            try {
                Socket(proxy).use { socket ->
                    socket.soTimeout = timeout
                    socket.connect(InetSocketAddress(host, port), timeout)
                    val (writer, reader) =
                        if (isHttps) {
                            val sslSocket =
                                (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(
                                    socket,
                                    host,
                                    port,
                                    true,
                                ) as javax.net.ssl.SSLSocket
                            sslSocket.startHandshake()
                            Pair(
                                sslSocket.outputStream.bufferedWriter(),
                                sslSocket.inputStream.bufferedReader(),
                            )
                        } else {
                            Pair(
                                socket.getOutputStream().bufferedWriter(),
                                socket.getInputStream().bufferedReader(),
                            )
                        }
                    writer.write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n")
                    writer.flush()
                    val firstLine = reader.readLine()
                    val latency = System.currentTimeMillis() - start
                    if (firstLine != null && firstLine.startsWith("HTTP/")) {
                        recordConnectivity(host, latency, null)
                        _uiEvent.trySend(
                            MainViewUiEvent.ShowSnackbar(
                                application.getString(
                                    R.string.connectivity_test_latency,
                                    latency.toInt(),
                                )
                            )
                        )
                    } else {
                        recordConnectivity(host, latency, "no HTTP response")
                        _uiEvent.trySend(
                            MainViewUiEvent.ShowSnackbar(
                                application.getString(R.string.connectivity_test_failed)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                recordConnectivity(
                    host,
                    System.currentTimeMillis() - start,
                    e.message ?: e.javaClass.simpleName,
                )
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(
                        application.getString(R.string.connectivity_test_failed)
                    )
                )
            }
        }
    }

    private fun recordConnectivity(host: String, latencyMs: Long, failure: String?) {
        Telemetry.event(
            application,
            if (failure == null) Level.INFO else Level.WARN,
            Category.CONNECTIVITY,
            if (failure == null) "connectivity.ok" else "connectivity.failed",
            if (failure == null) "Connectivity test to $host: $latencyMs ms"
            else "Connectivity test to $host failed: $failure",
            buildMap {
                put("host", host)
                put("latency_ms", latencyMs.toString())
                failure?.let { put("failure", it) }
            },
        )
    }

    fun registerTProxyServiceReceivers() {
        val application = application
        val startSuccessFilter = IntentFilter(TProxyService.ACTION_START)
        ContextCompat.registerReceiver(
            application,
            startReceiver,
            startSuccessFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val stopSuccessFilter = IntentFilter(TProxyService.ACTION_STOP)
        ContextCompat.registerReceiver(
            application,
            stopReceiver,
            stopSuccessFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        Log.d(TAG, "TProxyService receivers registered.")
    }

    fun unregisterTProxyServiceReceivers() {
        val application = application
        application.unregisterReceiver(startReceiver)
        application.unregisterReceiver(stopReceiver)
        Log.d(TAG, "TProxyService receivers unregistered.")
    }

    fun restoreDefaultGeoip(callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            fileManager.restoreDefaultGeoip()
            _settingsState.value =
                _settingsState.value.copy(
                    files =
                        _settingsState.value.files.copy(isGeoipCustom = prefs.customGeoipImported),
                    info =
                        _settingsState.value.info.copy(
                            geoipSummary = fileManager.getRuleFileSummary("geoip.dat")
                        ),
                )
            _uiEvent.trySend(
                MainViewUiEvent.ShowSnackbar(
                    application.getString(R.string.rule_file_restore_geoip_success)
                )
            )
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Restored default geoip.dat.")
                callback()
            }
        }
    }

    fun restoreDefaultGeosite(callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            fileManager.restoreDefaultGeosite()
            _settingsState.value =
                _settingsState.value.copy(
                    files =
                        _settingsState.value.files.copy(
                            isGeositeCustom = prefs.customGeositeImported
                        ),
                    info =
                        _settingsState.value.info.copy(
                            geositeSummary = fileManager.getRuleFileSummary("geosite.dat")
                        ),
                )
            _uiEvent.trySend(
                MainViewUiEvent.ShowSnackbar(
                    application.getString(R.string.rule_file_restore_geosite_success)
                )
            )
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Restored default geosite.dat.")
                callback()
            }
        }
    }

    fun cancelDownload(fileName: String) {
        viewModelScope.launch {
            if (fileName == "geoip.dat") {
                geoipDownloadJob?.cancel()
            } else {
                geositeDownloadJob?.cancel()
            }
            Log.d(TAG, "Download cancellation requested for $fileName")
        }
    }

    fun downloadRuleFile(url: String, fileName: String) {
        val currentJob = if (fileName == "geoip.dat") geoipDownloadJob else geositeDownloadJob
        if (currentJob?.isActive == true) {
            Log.w(TAG, "Download already in progress for $fileName")
            return
        }

        val job =
            viewModelScope.launch(Dispatchers.IO) {
                val progressFlow =
                    if (fileName == "geoip.dat") {
                        prefs.geoipUrl = url
                        _geoipDownloadProgress
                    } else {
                        prefs.geositeUrl = url
                        _geositeDownloadProgress
                    }

                val client =
                    OkHttpClient.Builder()
                        .apply {
                            if (_isServiceEnabled.value) {
                                proxy(
                                    Proxy(
                                        Proxy.Type.SOCKS,
                                        InetSocketAddress("127.0.0.1", prefs.socksPort),
                                    )
                                )
                            }
                        }
                        .build()

                try {
                    progressFlow.value = application.getString(R.string.connecting)

                    val request = Request.Builder().url(url).build()
                    val call = client.newCall(request)
                    val response = call.await()

                    if (!response.isSuccessful) {
                        throw IOException("Failed to download file: ${response.code}")
                    }

                    val body = response.body
                    val totalBytes = body.contentLength()
                    var bytesRead = 0L
                    var lastProgress = -1

                    body.byteStream().use { inputStream ->
                        val success =
                            fileManager.saveRuleFile(inputStream, fileName) { read ->
                                ensureActive()
                                bytesRead += read
                                if (totalBytes > 0) {
                                    val progress = (bytesRead * 100 / totalBytes).toInt()
                                    if (progress != lastProgress) {
                                        progressFlow.value =
                                            application.getString(R.string.downloading, progress)
                                        lastProgress = progress
                                    }
                                } else {
                                    if (lastProgress == -1) {
                                        progressFlow.value =
                                            application.getString(R.string.downloading_no_size)
                                        lastProgress = 0
                                    }
                                }
                            }
                        if (success) {
                            when (fileName) {
                                "geoip.dat" -> {
                                    _settingsState.value =
                                        _settingsState.value.copy(
                                            files =
                                                _settingsState.value.files.copy(
                                                    isGeoipCustom = prefs.customGeoipImported
                                                ),
                                            info =
                                                _settingsState.value.info.copy(
                                                    geoipSummary =
                                                        fileManager.getRuleFileSummary("geoip.dat")
                                                ),
                                        )
                                }

                                "geosite.dat" -> {
                                    _settingsState.value =
                                        _settingsState.value.copy(
                                            files =
                                                _settingsState.value.files.copy(
                                                    isGeositeCustom = prefs.customGeositeImported
                                                ),
                                            info =
                                                _settingsState.value.info.copy(
                                                    geositeSummary =
                                                        fileManager.getRuleFileSummary(
                                                            "geosite.dat"
                                                        )
                                                ),
                                        )
                                }
                            }
                            _uiEvent.trySend(
                                MainViewUiEvent.ShowSnackbar(
                                    application.getString(R.string.download_success)
                                )
                            )
                        } else {
                            _uiEvent.trySend(
                                MainViewUiEvent.ShowSnackbar(
                                    application.getString(R.string.download_failed)
                                )
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Download cancelled for $fileName")
                    _uiEvent.trySend(
                        MainViewUiEvent.ShowSnackbar(
                            application.getString(R.string.download_cancelled)
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download rule file", e)
                    _uiEvent.trySend(
                        MainViewUiEvent.ShowSnackbar(
                            application.getString(R.string.download_failed) + ": " + e.message
                        )
                    )
                } finally {
                    progressFlow.value = null
                    updateSettingsState()
                }
            }

        if (fileName == "geoip.dat") {
            geoipDownloadJob = job
        } else {
            geositeDownloadJob = job
        }

        job.invokeOnCompletion {
            if (fileName == "geoip.dat") {
                geoipDownloadJob = null
            } else {
                geositeDownloadJob = null
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWith(Result.failure(e))
                }
            }
        )
        continuation.invokeOnCancellation {
            try {
                cancel()
            } catch (_: Throwable) {}
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            _isCheckingForUpdates.value = true
            val client =
                OkHttpClient.Builder()
                    .apply {
                        if (_isServiceEnabled.value) {
                            proxy(
                                Proxy(
                                    Proxy.Type.SOCKS,
                                    InetSocketAddress("127.0.0.1", prefs.socksPort),
                                )
                            )
                        }
                    }
                    .build()

            val request =
                Request.Builder()
                    .url(application.getString(R.string.source_url) + "/releases/latest")
                    .head()
                    .build()

            try {
                val response = client.newCall(request).await()
                val location = response.request.url.toString()
                val latestTag = location.substringAfterLast("/tag/v")
                Log.d(TAG, "Latest version tag: $latestTag")
                val updateAvailable = compareVersions(latestTag) > 0
                if (updateAvailable) {
                    _newVersionAvailable.value = latestTag
                } else {
                    _uiEvent.trySend(
                        MainViewUiEvent.ShowSnackbar(
                            application.getString(R.string.no_new_version_available)
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(
                        application.getString(R.string.failed_to_check_for_updates) +
                            ": " +
                            e.message
                    )
                )
            } finally {
                _isCheckingForUpdates.value = false
            }
        }
    }

    fun downloadNewVersion(versionTag: String) {
        val url = application.getString(R.string.source_url) + "/releases/tag/v$versionTag"
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        application.startActivity(intent)
        _newVersionAvailable.value = null
    }

    fun clearNewVersionAvailable() {
        _newVersionAvailable.value = null
    }

    private fun compareVersions(version1: String): Int {
        val parts1 = version1.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 =
            BuildConfig.VERSION_NAME.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) {
                return p1.compareTo(p2)
            }
        }
        return 0
    }

    override fun onCleared() {
        closeStatsClients()
    }

    companion object {
        /** Two minutes of one-second polls. */
        const val THROUGHPUT_SAMPLES = 120
        private const val IPV4_REGEX =
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
        private val IPV4_PATTERN: Pattern = Pattern.compile(IPV4_REGEX)
        private const val IPV6_REGEX =
            "^(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80::(fe80(:[0-9a-fA-F]{0,4})?){0,4}%[0-9a-zA-Z]+|::(ffff(:0{1,4})?:)?((25[0-5]|(2[0-4]|1?\\d)?\\d)\\.){3}(25[0-5]|(2[0-4]|1?\\d)?\\d)|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1?\\d)?\\d)\\.){3}(25[0-5]|(2[0-4]|1?\\d)?\\d))$"
        private val IPV6_PATTERN: Pattern = Pattern.compile(IPV6_REGEX)

        @Suppress("DEPRECATION")
        fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return activityManager.getRunningServices(Int.MAX_VALUE).any { service ->
                serviceClass.name == service.service.className
            }
        }
    }
}

class MainViewModelFactory(private val application: Application) :
    ViewModelProvider.AndroidViewModelFactory(application) {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
