// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.simplexray.an.BuildConfig
import com.simplexray.an.R
import com.simplexray.an.activity.MainActivity
import com.simplexray.an.core.InstanceRegistry
import com.simplexray.an.core.InstanceStatus
import com.simplexray.an.core.InstanceSupervisor
import com.simplexray.an.core.XrayInstanceInfo
import com.simplexray.an.core.XrayLauncher
import com.simplexray.an.data.source.LogFileManager
import com.simplexray.an.prefs.Preferences
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TProxyService : VpnService() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val logBroadcastBuffer: MutableList<String> = mutableListOf()
    private val broadcastLogsRunnable = Runnable {
        synchronized(logBroadcastBuffer) {
            if (logBroadcastBuffer.isNotEmpty()) {
                val logUpdateIntent = Intent(ACTION_LOG_UPDATE)
                logUpdateIntent.setPackage(application.packageName)
                logUpdateIntent.putStringArrayListExtra(
                    EXTRA_LOG_DATA,
                    ArrayList(logBroadcastBuffer),
                )
                sendBroadcast(logUpdateIntent)
                logBroadcastBuffer.clear()
                Log.d(TAG, "Broadcasted a batch of logs.")
            }
        }
    }

    private lateinit var logFileManager: LogFileManager
    private lateinit var supervisor: InstanceSupervisor
    private var tunFd: ParcelFileDescriptor? = null

    // The foreground notification's channel, while it is showing.
    @Volatile private var notificationChannel: String? = null
    @Volatile private var instances: List<XrayInstanceInfo> = emptyList()

    override fun onCreate() {
        super.onCreate()
        logFileManager = LogFileManager(this)
        // Anything registered belongs to a previous process of this service, now gone.
        InstanceRegistry.clear(this)
        supervisor =
            InstanceSupervisor(
                XrayLauncher(this),
                onLog = ::handleLog,
                onChange = { infos ->
                    instances = infos
                    InstanceRegistry.publish(this, infos)
                    notificationChannel?.let { showNotification(it) }
                },
            )
        Log.d(TAG, "TProxyService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_DISCONNECT -> {
                stopXray()
                return START_NOT_STICKY
            }

            ACTION_START_INSTANCE -> {
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                if (!configPath.isNullOrEmpty()) {
                    Log.d(TAG, "Starting instance for config: $configPath")
                    // The selected config is the one carrying the VPN, whichever way it is started.
                    val isPrimary = configPath == Preferences(this).selectedConfigPath
                    supervisor.start(configPath, isPrimaryVpn = isPrimary)
                }
                return START_STICKY
            }

            ACTION_STOP_INSTANCE -> {
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                if (!configPath.isNullOrEmpty()) {
                    Log.d(TAG, "Stopping instance for config: $configPath")
                    supervisor.stop(configPath)
                }
                return START_STICKY
            }

            ACTION_RELOAD_CONFIG -> {
                val prefs = Preferences(this)
                val selectedConfigPath = prefs.selectedConfigPath
                if (!selectedConfigPath.isNullOrEmpty()) {
                    Log.d(TAG, "Received RELOAD_CONFIG action for primary instance.")
                    switchPrimary(prefs, selectedConfigPath)
                }
                return START_STICKY
            }

            ACTION_START -> {
                logFileManager.clearLogs()
                val prefs = Preferences(this)
                if (prefs.disableVpn) {
                    serviceScope.launch { runXrayProcess() }
                    val successIntent = Intent(ACTION_START)
                    successIntent.setPackage(application.packageName)
                    sendBroadcast(successIntent)

                    @Suppress("SameParameterValue") val channelName = "nosocks"
                    initNotificationChannel(channelName)
                    showNotification(channelName)
                } else {
                    startXray()
                }
                return START_STICKY
            }

            else -> {
                logFileManager.clearLogs()
                startXray()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationChannel = null
        handler.removeCallbacks(broadcastLogsRunnable)
        broadcastLogsRunnable.run()
        serviceScope.cancel()
        supervisor.close()
        Log.d(TAG, "TProxyService destroyed.")
        exitProcess(0)
    }

    override fun onRevoke() {
        stopXray()
        super.onRevoke()
    }

    private fun handleLog(tag: String, line: String) {
        val formattedLine = "[$tag] $line"
        logFileManager.appendLog(formattedLine)
        synchronized(logBroadcastBuffer) {
            logBroadcastBuffer.add(formattedLine)
            if (!handler.hasCallbacks(broadcastLogsRunnable)) {
                handler.postDelayed(broadcastLogsRunnable, BROADCAST_DELAY_MS)
            }
        }
    }

    private fun startXray() {
        startService()
        serviceScope.launch { runXrayProcess() }
    }

    private fun runXrayProcess() {
        val prefs = Preferences(applicationContext)
        val selectedConfigPath = prefs.selectedConfigPath ?: return

        Log.d(TAG, "Starting primary VPN instance: $selectedConfigPath")
        supervisor.start(selectedConfigPath, isPrimaryVpn = true)

        // Start secondary active instances if configured
        prefs.activeConfigPaths.forEach { path ->
            if (path != selectedConfigPath && File(path).exists()) {
                Log.d(TAG, "Starting secondary active instance: $path")
                supervisor.start(path, isPrimaryVpn = false)
            }
        }
    }

    /**
     * Makes selectedConfigPath the instance carrying the VPN. The old one is stopped first, since
     * it holds the inbound ports the new one needs; if it is also switched on as a secondary
     * instance, it is started again as one afterwards.
     */
    private fun switchPrimary(prefs: Preferences, selectedConfigPath: String) {
        val previous = instances.filter { it.isPrimaryVpn && it.configPath != selectedConfigPath }
        previous.forEach { supervisor.stop(it.configPath) }
        supervisor.start(selectedConfigPath, isPrimaryVpn = true)
        val active = prefs.activeConfigPaths
        previous
            .filter { it.configPath in active }
            .forEach { supervisor.start(it.configPath, isPrimaryVpn = false) }
    }

    private fun stopXray() {
        Log.d(TAG, "stopXray called")
        serviceScope.cancel()
        // onDestroy, which follows, stops the instances.
        stopService()
    }

    private fun startService() {
        if (tunFd != null) return
        val prefs = Preferences(this)
        val builder = getVpnBuilder(prefs)
        tunFd = builder.establish()
        if (tunFd == null) {
            stopXray()
            return
        }
        val tproxyFile = File(cacheDir, "tproxy.conf")
        try {
            tproxyFile.createNewFile()
            FileOutputStream(tproxyFile, false).use { fos ->
                val tproxyConf = getTproxyConf(prefs)
                fos.write(tproxyConf.toByteArray())
            }
        } catch (e: IOException) {
            Log.e(TAG, e.toString())
            stopXray()
            return
        }
        val fd = tunFd?.fd
        if (fd == null) {
            Log.e(TAG, "tunFd is null after establish()")
            stopXray()
            return
        }
        if (!TProxyStartService(tproxyFile.absolutePath, fd)) {
            Log.e(TAG, "hev-socks5-tunnel failed to start")
            stopXray()
            return
        }

        val successIntent = Intent(ACTION_START)
        successIntent.setPackage(application.packageName)
        sendBroadcast(successIntent)
        @Suppress("SameParameterValue") val channelName = "socks5"
        initNotificationChannel(channelName)
        showNotification(channelName)
    }

    private fun getVpnBuilder(prefs: Preferences): Builder =
        Builder().apply {
            setBlocking(false)
            setMtu(prefs.tunnelMtu)

            setMetered(false)

            if (prefs.httpProxyEnabled) {
                setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", prefs.socksPort))
            }
            if (prefs.ipv4) {
                addAddress(prefs.tunnelIpv4Address, prefs.tunnelIpv4Prefix)
                VpnRoutes.ipv4(prefs.bypassLan).forEach { addRoute(it.address, it.prefixLength) }
                prefs.dnsIpv4.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
            }
            if (prefs.ipv6) {
                addAddress(prefs.tunnelIpv6Address, prefs.tunnelIpv6Prefix)
                VpnRoutes.ipv6(prefs.bypassLan).forEach { addRoute(it.address, it.prefixLength) }
                prefs.dnsIpv6.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
            }

            prefs.apps?.forEach { appName ->
                appName?.let { name ->
                    try {
                        when {
                            prefs.bypassSelectedApps -> addDisallowedApplication(name)
                            else -> addAllowedApplication(name)
                        }
                    } catch (ignored: PackageManager.NameNotFoundException) {}
                }
            }
            if (prefs.bypassSelectedApps || prefs.apps.isNullOrEmpty())
                addDisallowedApplication(BuildConfig.APPLICATION_ID)
        }

    private fun stopService() {
        notificationChannel = null
        tunFd?.let {
            try {
                it.close()
            } catch (ignored: IOException) {} finally {
                tunFd = null
            }
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            TProxyStopService()
        }
        exit()
    }

    /** Shows, or updates, the foreground notification with the primary instance's state. */
    private fun showNotification(channelName: String) {
        notificationChannel = channelName
        val i = Intent(this, MainActivity::class.java)
        val pi =
            PendingIntent.getActivity(
                this,
                0,
                i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val status = statusText()
        val notify =
            NotificationCompat.Builder(this, channelName)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(status)
                .setStyle(NotificationCompat.BigTextStyle().bigText(status))
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notify)
        } else {
            startForeground(1, notify, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }

    private fun statusText(): String? {
        val all = instances
        val primary = all.firstOrNull { it.isPrimaryVpn } ?: return null
        val text =
            when (primary.status) {
                InstanceStatus.STARTING -> getString(R.string.connecting)
                InstanceStatus.RUNNING -> primary.configName
                InstanceStatus.RESTARTING -> getString(R.string.reconnecting, primary.configName)
                InstanceStatus.ERROR ->
                    getString(R.string.connection_failed, primary.configName) +
                        primary.errorMessage?.let { "\n$it" }.orEmpty()
            }
        val others = all.size - 1
        return if (others > 0) {
            resources.getQuantityString(R.plurals.notification_more_instances, others, text, others)
        } else text
    }

    private fun exit() {
        val stopIntent = Intent(ACTION_STOP)
        stopIntent.setPackage(application.packageName)
        sendBroadcast(stopIntent)
        stopSelf()
    }

    @Suppress("SameParameterValue")
    private fun initNotificationChannel(channelName: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val name: CharSequence = getString(R.string.app_name)
        val channel = NotificationChannel(channelName, name, NotificationManager.IMPORTANCE_DEFAULT)
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_CONNECT: String = "com.simplexray.an.CONNECT"
        const val ACTION_DISCONNECT: String = "com.simplexray.an.DISCONNECT"
        const val ACTION_START: String = "com.simplexray.an.START"
        const val ACTION_STOP: String = "com.simplexray.an.STOP"
        const val ACTION_LOG_UPDATE: String = "com.simplexray.an.LOG_UPDATE"
        const val ACTION_RELOAD_CONFIG: String = "com.simplexray.an.RELOAD_CONFIG"
        const val ACTION_START_INSTANCE: String = "com.simplexray.an.START_INSTANCE"
        const val ACTION_STOP_INSTANCE: String = "com.simplexray.an.STOP_INSTANCE"
        const val EXTRA_CONFIG_PATH: String = "config_path"
        const val EXTRA_LOG_DATA: String = "log_data"
        private const val TAG = "VpnService"
        private const val BROADCAST_DELAY_MS: Long = 3000

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int): Boolean

        @JvmStatic @Suppress("FunctionName") private external fun TProxyStopService(): Boolean

        // Unused, but hev-socks5-tunnel's JNI_OnLoad registers it and fails if it
        // is missing.
        @JvmStatic
        @Suppress("FunctionName", "unused")
        private external fun TProxyIsRunning(): Boolean

        @JvmStatic @Suppress("FunctionName") private external fun TProxyGetStats(): LongArray?

        fun getNativeLibraryDir(context: Context?): String? {
            if (context == null) {
                Log.e(TAG, "Context is null")
                return null
            }
            try {
                val applicationInfo = context.applicationInfo
                if (applicationInfo != null) {
                    val nativeLibraryDir = applicationInfo.nativeLibraryDir
                    Log.d(TAG, "Native Library Directory: $nativeLibraryDir")
                    return nativeLibraryDir
                } else {
                    Log.e(TAG, "ApplicationInfo is null")
                    return null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting native library dir", e)
                return null
            }
        }

        private fun getTproxyConf(prefs: Preferences): String {
            var tproxyConf =
                """misc:
  task-stack-size: ${prefs.taskStackSize}
tunnel:
  mtu: ${prefs.tunnelMtu}
"""
            tproxyConf +=
                """socks5:
  port: ${prefs.socksPort}
  address: '${prefs.socksAddress}'
  udp: '${if (prefs.udpInTcp) "tcp" else "udp"}'
"""
            if (prefs.socksUsername.isNotEmpty() && prefs.socksPassword.isNotEmpty()) {
                tproxyConf += "  username: '" + prefs.socksUsername + "'\n"
                tproxyConf += "  password: '" + prefs.socksPassword + "'\n"
            }
            return tproxyConf
        }
    }
}
