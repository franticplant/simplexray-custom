// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.core

import android.content.Context
import android.util.Log
import com.simplexray.an.common.ConfigUtils
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

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun toInfo(): XrayInstanceInfo {
        return XrayInstanceInfo(
            configPath = configPath,
            configName = configName,
            apiAddress = apiAddress,
            apiPort = apiPort,
            isPrimaryVpn = isPrimaryVpn,
            status = status,
            errorMessage = errorMessage,
        )
    }
}

object XrayInstanceManager {
    private val instances = ConcurrentHashMap<String, RunningInstance>()

    // Set by startInstance; every state change is published to InstanceRegistry with it.
    @Volatile private var appContext: Context? = null

    private fun publish() {
        appContext?.let { InstanceRegistry.publish(it, getAllInstanceInfos()) }
    }

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
        appContext = context.applicationContext
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
        publish()

        runningInstance.scope.launch {
            try {
                Log.d(
                    TAG,
                    "Launching Xray core instance for $configName on API $apiAddress:$apiPort",
                )
                val libraryDir = TProxyService.getNativeLibraryDir(context) ?: return@launch
                val xrayExecutable = "$libraryDir/libxray.so"

                val injectedConfig =
                    ConfigUtils.injectStatsService(apiAddress, apiPort, configContent)

                val filesDir = context.filesDir
                val processBuilder = ProcessBuilder(xrayExecutable)
                val environment = processBuilder.environment()
                environment["XRAY_LOCATION_ASSET"] = filesDir.path
                processBuilder.directory(filesDir)
                processBuilder.redirectErrorStream(true)

                val process = processBuilder.start()
                runningInstance.process = process
                runningInstance.status = InstanceStatus.RUNNING
                publish()

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
                if (runningInstance.status != InstanceStatus.ERROR) {
                    runningInstance.status = InstanceStatus.STOPPED
                }
                publish()
            }
        }

        return true
    }

    fun stopInstance(configPath: String) {
        instances.remove(configPath)?.let { instance ->
            Log.d(TAG, "Stopping instance: ${instance.configName}")
            instance.status = InstanceStatus.STOPPED
            instance.process?.destroy()
            instance.process = null
            instance.scope.cancel()
            publish()
        }
    }

    fun stopAll() {
        Log.d(TAG, "Stopping all Xray instances")
        instances.keys.toList().forEach { path ->
            stopInstance(path)
        }
    }
}
