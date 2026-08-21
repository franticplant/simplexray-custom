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
import com.simplexray.an.core.XrayInstanceManager
import com.simplexray.an.data.source.LogFileManager
import com.simplexray.an.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.system.exitProcess

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
                    EXTRA_LOG_DATA, ArrayList(logBroadcastBuffer)
                )
                sendBroadcast(logUpdateIntent)
                logBroadcastBuffer.clear()
                Log.d(TAG, "Broadcasted a batch of logs.")
            }
        }
    }

    private lateinit var logFileManager: LogFileManager
    private var tunFd: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        logFileManager = LogFileManager(this)
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
                    XrayInstanceManager.startInstance(
                        applicationContext,
                        configPath,
                        isPrimaryVpn = false
                    ) { tag, line -> handleLog(tag, line) }
                }
                return START_STICKY
            }

            ACTION_STOP_INSTANCE -> {
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                if (!configPath.isNullOrEmpty()) {
                    Log.d(TAG, "Stopping instance for config: $configPath")
                    XrayInstanceManager.stopInstance(configPath)
                }
                return START_STICKY
            }

            ACTION_RELOAD_CONFIG -> {
                val prefs = Preferences(this)
                val selectedConfigPath = prefs.selectedConfigPath
                if (!selectedConfigPath.isNullOrEmpty()) {
                    Log.d(TAG, "Received RELOAD_CONFIG action for primary instance.")
                    XrayInstanceManager.startInstance(
                        applicationContext,
                        selectedConfigPath,
                        isPrimaryVpn = true
                    ) { tag, line -> handleLog(tag, line) }
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
                    createNotification(channelName)
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
        handler.removeCallbacks(broadcastLogsRunnable)
        broadcastLogsRunnable.run()
        serviceScope.cancel()
        XrayInstanceManager.stopAll()
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
        XrayInstanceManager.startInstance(
            applicationContext,
            selectedConfigPath,
            isPrimaryVpn = true
        ) { tag, line -> handleLog(tag, line) }

        // Start secondary active instances if configured
        val activePaths = prefs.activeConfigPaths
        activePaths.forEach { path ->
            if (path != selectedConfigPath && File(path).exists()) {
                Log.d(TAG, "Starting secondary active instance: $path")
                XrayInstanceManager.startInstance(
                    applicationContext,
                    path,
                    isPrimaryVpn = false
                ) { tag, line -> handleLog(tag, line) }
            }
        }
    }

    private fun stopXray() {
        Log.d(TAG, "stopXray called")
        serviceScope.cancel()
        XrayInstanceManager.stopAll()
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
        tunFd?.fd?.let { fd ->
            TProxyStartService(tproxyFile.absolutePath, fd)
        } ?: run {
            Log.e(TAG, "tunFd is null after establish()")
            stopXray()
            return
        }

        val successIntent = Intent(ACTION_START)
        successIntent.setPackage(application.packageName)
        sendBroadcast(successIntent)
        @Suppress("SameParameterValue") val channelName = "socks5"
        initNotificationChannel(channelName)
        createNotification(channelName)
    }

    private fun getVpnBuilder(prefs: Preferences): Builder = Builder().apply {
        setBlocking(false)
        setMtu(prefs.tunnelMtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setMetered(false)
        }

        if (prefs.bypassLan) {
            addRoute("10.0.0.0", 8)
            addRoute("172.16.0.0", 12)
            addRoute("192.168.0.0", 16)
        }
        if (prefs.httpProxyEnabled) {
            setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", prefs.socksPort))
        }
        if (prefs.ipv4) {
            addAddress(prefs.tunnelIpv4Address, prefs.tunnelIpv4Prefix)
            addRoute("0.0.0.0", 0)
            prefs.dnsIpv4.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
        }
        if (prefs.ipv6) {
            addAddress(prefs.tunnelIpv6Address, prefs.tunnelIpv6Prefix)
            addRoute("::", 0)
            prefs.dnsIpv6.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
        }

        prefs.apps?.forEach { appName ->
            appName?.let { name ->
                try {
                    when {
                        prefs.bypassSelectedApps -> addDisallowedApplication(name)
                        else -> addAllowedApplication(name)
                    }
                } catch (ignored: PackageManager.NameNotFoundException) {
                }
            }
        }
        if (prefs.bypassSelectedApps || prefs.apps.isNullOrEmpty())
            addDisallowedApplication(BuildConfig.APPLICATION_ID)
    }

    private fun stopService() {
        tunFd?.let {
            try {
                it.close()
            } catch (ignored: IOException) {
            } finally {
                tunFd = null
            }
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            TProxyStopService()
        }
        exit()
    }

    @Suppress("SameParameterValue")
    private fun createNotification(channelName: String) {
        val i = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, channelName)
        val notify = notification.setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_stat_name).setContentIntent(pi).build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notify)
        } else {
            startForeground(1, notify, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
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
        private external fun TProxyStartService(configPath: String, fd: Int)

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService()

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?

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
            var tproxyConf = """misc:
  task-stack-size: ${prefs.taskStackSize}
tunnel:
  mtu: ${prefs.tunnelMtu}
"""
            tproxyConf += """socks5:
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
