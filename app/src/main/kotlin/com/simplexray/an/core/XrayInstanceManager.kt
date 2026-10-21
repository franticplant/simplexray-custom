// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.core

import android.content.Context
import android.util.Log
import com.simplexray.an.common.ConfigUtils
import com.simplexray.an.common.CoreStatsClient
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.service.TProxyService
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "XrayInstanceManager"

class RunningInstance(
    val configPath: String,
    val configName: String,
    val isPrimaryVpn: Boolean,
    val apiAddress: String,
    val apiPort: Int,
) {
    @Volatile var process: Process? = null

    @Volatile var status: InstanceStatus = InstanceStatus.STARTING

    @Volatile var errorMessage: String? = null

    var statsClient: CoreStatsClient? = null
    var uplink: Long = 0
    var downlink: Long = 0
    var numGoroutine: Int = 0
    var alloc: Long = 0
    var uptime: Int = 0

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun toInfo(): XrayInstanceInfo {
        return XrayInstanceInfo(
            configPath = configPath,
            configName = configName,
            apiAddress = apiAddress,
            apiPort = apiPort,
            isPrimaryVpn = isPrimaryVpn,
            status = status,
            uplink = uplink,
            downlink = downlink,
            numGoroutine = numGoroutine,
            alloc = alloc,
            uptime = uptime,
            errorMessage = errorMessage,
        )
    }
}

object XrayInstanceManager {
    private val instances = ConcurrentHashMap<String, RunningInstance>()

    fun getRunningInstance(configPath: String): RunningInstance? = instances[configPath]

    fun getAllInstanceInfos(): List<XrayInstanceInfo> {
        return instances.values.map { it.toInfo() }
    }

    fun isInstanceRunning(configPath: String): Boolean {
        return instances[configPath]?.status == InstanceStatus.RUNNING
    }

    private fun findAvailablePort(excludedPorts: Set<Int>): Int? {
        (10000..65535).shuffled().forEach { port ->
            if (port in excludedPorts) return@forEach
            runCatching {
                ServerSocket(port).use { socket ->
                    socket.reuseAddress = true
                }
                port
            }
                .onFailure {
                    Log.d(TAG, "Port $port unavailable: ${it.message}")
                }
                .onSuccess {
                    return port
                }
        }
        return null
    }

    fun startInstance(
        context: Context,
        configPath: String,
        isPrimaryVpn: Boolean,
        onLogLine: (tag: String, line: String) -> Unit,
    ): Boolean {
        val configFile = File(configPath)
        if (!configFile.exists()) {
            Log.e(TAG, "Config file does not exist: $configPath")
            return false
        }

        // Stop existing instance if already running
        stopInstance(configPath)

        val configName = configFile.name.removeSuffix(".json")
        val configContent = configFile.readText()
        val extractedPorts = ConfigUtils.extractPortsFromJson(configContent)

        val apiPort =
            findAvailablePort(extractedPorts)
                ?: run {
                    Log.e(TAG, "Failed to find available API port for $configName")
                    return false
                }

        val octet2 = (0..255).random()
        val octet3 = (0..255).random()
        val octet4 = (1..254).random()
        val apiAddress = "127.$octet2.$octet3.$octet4"

        val runningInstance =
            RunningInstance(
                configPath = configPath,
                configName = configName,
                isPrimaryVpn = isPrimaryVpn,
                apiAddress = apiAddress,
                apiPort = apiPort,
            )

        instances[configPath] = runningInstance

        runningInstance.scope.launch {
            try {
                Log.d(
                    TAG,
                    "Launching Xray core instance for $configName on API $apiAddress:$apiPort",
                )
                val libraryDir = TProxyService.getNativeLibraryDir(context) ?: return@launch
                val xrayExecutable = "$libraryDir/libxray.so"

                val prefs = Preferences(context)
                // Set temporary API port and address in prefs for ConfigUtils injection
                prefs.apiPort = apiPort
                prefs.apiAddress = apiAddress

                val injectedConfig = ConfigUtils.injectStatsService(prefs, configContent)

                val filesDir = context.filesDir
                val processBuilder = ProcessBuilder(xrayExecutable)
                val environment = processBuilder.environment()
                environment["XRAY_LOCATION_ASSET"] = filesDir.path
                processBuilder.directory(filesDir)
                processBuilder.redirectErrorStream(true)

                val process = processBuilder.start()
                runningInstance.process = process
                runningInstance.status = InstanceStatus.RUNNING

                // Write config to process stdin
                process.outputStream.use { os ->
                    os.write(injectedConfig.toByteArray())
                    os.flush()
                }

                // Read output logs
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { logStr ->
                        onLogLine(configName, logStr)
                    }
                }
            } catch (e: InterruptedIOException) {
                Log.d(TAG, "Instance $configName reading interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "Error running instance $configName", e)
                runningInstance.status = InstanceStatus.ERROR
                runningInstance.errorMessage = e.message
            } finally {
                Log.d(TAG, "Instance $configName process terminated.")
                runningInstance.status = InstanceStatus.STOPPED
                runningInstance.statsClient?.close()
                runningInstance.statsClient = null
            }
        }

        return true
    }

    suspend fun updateStats(configPath: String) {
        val instance = instances[configPath] ?: return
        if (instance.status != InstanceStatus.RUNNING) return

        if (instance.statsClient == null) {
            instance.statsClient = CoreStatsClient.create(instance.apiAddress, instance.apiPort)
        }

        val client = instance.statsClient ?: return
        val stats = client.getSystemStats()
        val traffic = client.getTraffic()

        if (stats != null) {
            instance.numGoroutine = stats.numGoroutine
            instance.alloc = stats.alloc
            instance.uptime = stats.uptime
        }

        if (traffic != null) {
            instance.uplink = traffic.uplink
            instance.downlink = traffic.downlink
        }
    }

    suspend fun updateAllStats() {
        instances.keys.forEach { path ->
            updateStats(path)
        }
    }

    fun stopInstance(configPath: String) {
        instances.remove(configPath)?.let { instance ->
            Log.d(TAG, "Stopping instance: ${instance.configName}")
            instance.status = InstanceStatus.STOPPED
            instance.process?.destroy()
            instance.process = null
            instance.statsClient?.close()
            instance.statsClient = null
            instance.scope.cancel()
        }
    }

    fun stopAll() {
        Log.d(TAG, "Stopping all Xray instances")
        instances.keys.toList().forEach { path ->
            stopInstance(path)
        }
    }
}
