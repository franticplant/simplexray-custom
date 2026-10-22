// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.core

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Runs Xray instances, one per config file, and keeps them running.
 *
 * An instance is STARTING until its API port accepts connections, then RUNNING. If it exits after
 * running stably it is restarted with backoff (RESTARTING); if it exits before that, it has a
 * problem a restart will not fix (a bad config, a port in use) and goes to ERROR with the reason
 * taken from its last log lines. Stopping an instance removes it.
 *
 * start and stop return at once; the work runs in the background, one request at a time in the
 * order they were made, so a stop followed by a start of the same config never overlaps.
 */
class InstanceSupervisor(
    private val launcher: Launcher,
    private val onLog: (configName: String, line: String) -> Unit,
    private val onChange: (List<XrayInstanceInfo>) -> Unit,
    private val policy: RestartPolicy = RestartPolicy(),
    private val probe: (address: String, port: Int) -> Boolean = ::apiAnswers,
) {
    interface Launcher {
        /** Ports the config listens on or connects to, which the API port must avoid. */
        fun configPorts(configPath: String): Set<Int>

        /** Starts Xray for the config, with its API on apiAddress:apiPort. */
        fun launch(configPath: String, apiAddress: String, apiPort: Int): Process
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @OptIn(ExperimentalCoroutinesApi::class) private val ops = Dispatchers.IO.limitedParallelism(1)
    private val instances = LinkedHashMap<String, Instance>() // guarded by itself
    private val publishLock = Any()
    @Volatile private var closed = false

    fun start(configPath: String, isPrimaryVpn: Boolean) {
        scope.launch(ops) {
            stopNow(configPath)
            if (!closed) startNow(configPath, isPrimaryVpn)
        }
    }

    fun stop(configPath: String) {
        scope.launch(ops) { stopNow(configPath) }
    }

    fun snapshot(): List<XrayInstanceInfo> =
        synchronized(instances) { instances.values.toList() }.map { it.info() }

    /** Stops every instance and waits for them to exit. Nothing can be started afterwards. */
    fun close() {
        closed = true
        runBlocking {
            withContext(ops) {
                synchronized(instances) { instances.keys.toList() }.forEach { stopNow(it) }
            }
        }
        scope.cancel()
    }

    private fun startNow(configPath: String, isPrimaryVpn: Boolean) {
        val instance =
            Instance(configPath, File(configPath).name.removeSuffix(".json"), isPrimaryVpn)
        synchronized(instances) { instances[configPath] = instance }
        instance.job = scope.launch { supervise(instance) }
        publish()
    }

    /** Kills the instance's process and waits for it, then removes the instance. */
    private fun stopNow(configPath: String) {
        val instance = synchronized(instances) { instances[configPath] } ?: return
        instance.stopping = true
        instance.job.cancel()
        instance.process?.let { p ->
            p.destroy()
            if (!p.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                p.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        }
        synchronized(instances) { instances.remove(configPath) }
        publish()
    }

    private suspend fun supervise(instance: Instance) {
        while (true) {
            val run = runOnce(instance)
            if (instance.stopping) return
            val restarts = policy.restartsAfter(run.healthyMs, instance.restarts)
            instance.errorMessage = run.failure
            if (restarts == null) {
                onLog(instance.configName, "Not restarting: ${run.failure}")
                instance.status = InstanceStatus.ERROR
                publish()
                return
            }
            val delayMs = policy.delayMs(restarts)
            onLog(instance.configName, "Restarting in ${delayMs / 1000} s: ${run.failure}")
            instance.restarts = restarts
            instance.status = InstanceStatus.RESTARTING
            publish()
            delay(delayMs)
        }
    }

    private class RunResult(val healthyMs: Long, val failure: String)

    /** Runs Xray once, until it exits or is stopped. */
    private suspend fun runOnce(instance: Instance): RunResult = coroutineScope {
        val process =
            try {
                val excluded =
                    launcher.configPorts(instance.configPath) +
                        synchronized(instances) { instances.values.map { it.apiPort } }
                instance.apiAddress = randomLoopbackAddress()
                instance.apiPort = findFreePort(excluded)
                instance.status = InstanceStatus.STARTING
                publish()
                launcher.launch(instance.configPath, instance.apiAddress, instance.apiPort)
            } catch (e: Exception) {
                return@coroutineScope RunResult(0, "Could not start Xray: ${e.message}")
            }
        instance.process = process
        if (instance.stopping) process.destroy() // the stop raced the launch

        val healthySince = AtomicLong(0)
        val timedOut = AtomicBoolean(false)
        val health = launch {
            val deadline = System.currentTimeMillis() + policy.startupTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (probe(instance.apiAddress, instance.apiPort)) {
                    healthySince.set(System.currentTimeMillis())
                    instance.status = InstanceStatus.RUNNING
                    instance.errorMessage = null
                    publish()
                    return@launch
                }
                delay(PROBE_INTERVAL_MS)
            }
            timedOut.set(true)
            process.destroy()
        }

        val recent = ArrayDeque<String>()
        withContext(Dispatchers.IO) {
            process.inputStream.bufferedReader().forEachLine { line ->
                recent.addLast(line)
                if (recent.size > RECENT_LINES) recent.removeFirst()
                onLog(instance.configName, line)
            }
        }
        val code = process.waitFor()
        health.cancel()

        val since = healthySince.get()
        RunResult(
            healthyMs = if (since > 0) System.currentTimeMillis() - since else 0,
            failure =
                if (timedOut.get())
                    "Xray did not open its API port within ${policy.startupTimeoutMs / 1000} s"
                else describeExit(code, recent),
        )
    }

    private fun publish() {
        synchronized(publishLock) { onChange(snapshot()) }
    }

    private class Instance(
        val configPath: String,
        val configName: String,
        val isPrimaryVpn: Boolean,
    ) {
        lateinit var job: Job
        @Volatile var process: Process? = null
        @Volatile var stopping = false
        @Volatile var apiAddress = ""
        @Volatile var apiPort = 0
        @Volatile var status = InstanceStatus.STARTING
        @Volatile var errorMessage: String? = null
        @Volatile var restarts = 0

        fun info() =
            XrayInstanceInfo(
                configPath = configPath,
                configName = configName,
                apiAddress = apiAddress,
                apiPort = apiPort,
                isPrimaryVpn = isPrimaryVpn,
                status = status,
                errorMessage = errorMessage,
                restarts = restarts,
            )
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 3_000L
        private const val PROBE_INTERVAL_MS = 100L
        private const val RECENT_LINES = 20

        fun apiAnswers(address: String, port: Int): Boolean = runCatching {
            Socket().use { it.connect(InetSocketAddress(address, port), 200) }
        }
            .isSuccess

        /** A random 127.x.y.z, so instances' APIs are not all on 127.0.0.1. */
        private fun randomLoopbackAddress() =
            "127.${(0..255).random()}.${(0..255).random()}.${(1..254).random()}"

        private fun findFreePort(excluded: Set<Int>): Int {
            repeat(100) {
                val port = (10000..65535).random()
                if (port !in excluded && runCatching { ServerSocket(port).close() }.isSuccess) {
                    return port
                }
            }
            throw IllegalStateException("no free port for the API")
        }

        /**
         * Why Xray exited, from its exit code and its last log lines: the last line that reports a
         * failure, else the last line.
         */
        internal fun describeExit(code: Int, lines: Collection<String>): String {
            val reason =
                lines.lastOrNull { FAILURE.containsMatchIn(it) }
                    ?: lines.lastOrNull { it.isNotBlank() }
            val summary = "Xray exited with code $code"
            return if (reason == null) summary else "$summary: ${reason.trim().take(300)}"
        }

        private val FAILURE = Regex("fail|error|panic|fatal", RegexOption.IGNORE_CASE)
    }
}

/** When to restart an instance that exited, and how long to wait first. */
class RestartPolicy(
    /** A run shorter than this never got going, and a restart would not help. */
    val stableAfterMs: Long = 5_000,
    /** A run this long clears the restart count. */
    val resetAfterMs: Long = 60_000,
    val maxRestarts: Int = 5,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
    val startupTimeoutMs: Long = 20_000,
) {
    /**
     * The restart count to record before restarting an instance that exited after running healthy
     * for healthyMs, having been restarted restarts times already; null to give up.
     */
    fun restartsAfter(healthyMs: Long, restarts: Int): Int? {
        if (healthyMs < stableAfterMs) return null
        val previous = if (healthyMs >= resetAfterMs) 0 else restarts
        return (previous + 1).takeIf { it <= maxRestarts }
    }

    /** Backoff before the restarts-th restart: base, doubling, capped. */
    fun delayMs(restarts: Int): Long =
        (baseDelayMs shl (restarts - 1).coerceIn(0, 20)).coerceAtMost(maxDelayMs)
}
