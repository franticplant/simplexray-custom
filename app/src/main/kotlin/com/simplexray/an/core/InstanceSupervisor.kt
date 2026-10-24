// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.core

import java.io.File
import java.io.IOException
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
    private val listener: Listener = object : Listener {},
) {
    /** Each Xray process's lifecycle, for telemetry. Called on the supervisor's threads. */
    interface Listener {
        fun runStarted(info: XrayInstanceInfo) {}

        fun runReady(info: XrayInstanceInfo) {}

        /**
         * The process exited, or could not be started (exitCode null). failure is null for a
         * requested stop.
         */
        fun runEnded(
            info: XrayInstanceInfo,
            exitCode: Int?,
            outcome: RunOutcome,
            failure: String?,
        ) {}
    }

    enum class RunOutcome {
        /** Stopped on request. */
        STOPPED,
        /** Exited after running stably; restarted. */
        CRASHED,
        /** Exited before running stably, or crashed too often; not restarted. */
        FAILED,
        /** Its API never answered. */
        TIMEOUT,
    }

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
        // Destroy before cancelling the job: destroying closes the process's output stream, which
        // makes the blocked log reader return, and only then is the job free to be cancelled (it
        // may be waiting out a restart delay instead).
        instance.process?.let { p ->
            p.destroy()
            if (!p.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                p.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            endRun(instance, runCatching { p.exitValue() }.getOrNull(), RunOutcome.STOPPED, null)
        }
        instance.job.cancel()
        synchronized(instances) { instances.remove(configPath) }
        publish()
    }

    private suspend fun supervise(instance: Instance) {
        while (true) {
            val run = runOnce(instance)
            if (instance.stopping) return
            val restarts = policy.restartsAfter(run.healthyMs, instance.restarts)
            val outcome =
                when {
                    run.timedOut -> RunOutcome.TIMEOUT
                    restarts != null -> RunOutcome.CRASHED
                    else -> RunOutcome.FAILED
                }
            endRun(instance, run.exitCode, outcome, run.failure)
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
            if (instance.stopping) return
        }
    }

    private class RunResult(
        val healthyMs: Long,
        val failure: String,
        val exitCode: Int? = null,
        val timedOut: Boolean = false,
    )

    /** Reports the end of the instance's current run, once. */
    private fun endRun(instance: Instance, exitCode: Int?, outcome: RunOutcome, failure: String?) {
        if (instance.runOpen.compareAndSet(true, false)) {
            listener.runEnded(instance.info(), exitCode, outcome, failure)
        }
    }

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
                instance.runOpen.set(true)
                listener.runStarted(instance.info())
                launcher.launch(instance.configPath, instance.apiAddress, instance.apiPort)
            } catch (e: Exception) {
                // A config that cannot be read fails before the run is reported; report it now.
                if (instance.runOpen.compareAndSet(false, true))
                    listener.runStarted(instance.info())
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
                    listener.runReady(instance.info())
                    return@launch
                }
                delay(PROBE_INTERVAL_MS)
            }
            timedOut.set(true)
            process.destroy()
        }

        val recent = ArrayDeque<String>()
        withContext(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    recent.addLast(line)
                    if (recent.size > RECENT_LINES) recent.removeFirst()
                    onLog(instance.configName, line)
                }
            } catch (e: IOException) {
                // Closing the process (on stop or on the startup timeout) closes its output stream
                // under the reader; Android reports that as InterruptedIOException. The exit code
                // and the lines read so far are still valid.
                if (!instance.stopping) {
                    onLog(instance.configName, "Could not read Xray output: ${e.message}")
                }
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
            exitCode = code,
            timedOut = timedOut.get(),
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
        /** A run was reported started and not yet ended. */
        val runOpen = AtomicBoolean(false)

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
         * failure, else the last line that is not routine traffic logging (which says nothing about
         * why it stopped).
         */
        internal fun describeExit(code: Int, lines: Collection<String>): String {
            val reason =
                lines.lastOrNull { FAILURE.containsMatchIn(it) }
                    ?: lines.lastOrNull { it.isNotBlank() && !ROUTINE.containsMatchIn(it) }
            val summary = "Xray exited with code $code"
            return if (reason == null) summary else "$summary: ${reason.trim().take(300)}"
        }

        private val FAILURE = Regex("fail|error|panic|fatal", RegexOption.IGNORE_CASE)
        private val ROUTINE = Regex("""\baccepted\b|\[(Info|Debug)]""")
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
