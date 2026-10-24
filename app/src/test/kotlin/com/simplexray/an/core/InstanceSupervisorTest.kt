// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Supervises real `sh` processes standing in for Xray. */
class InstanceSupervisorTest {
    private val launched: MutableList<Process> = Collections.synchronizedList(mutableListOf())
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val statuses: MutableList<InstanceStatus> =
        Collections.synchronizedList(mutableListOf())
    private var supervisor: InstanceSupervisor? = null
    // The listener's calls, as "started a", "ready a", "ended a FAILED 23".
    private val runs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val listener =
        object : InstanceSupervisor.Listener {
            override fun runStarted(info: XrayInstanceInfo) {
                runs += "started ${info.configName}"
            }

            override fun runReady(info: XrayInstanceInfo) {
                runs += "ready ${info.configName}"
            }

            override fun runEnded(
                info: XrayInstanceInfo,
                exitCode: Int?,
                outcome: InstanceSupervisor.RunOutcome,
                failure: String?,
            ) {
                runs += "ended ${info.configName} $outcome $exitCode"
            }
        }

    private fun awaitRuns(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (runs.size < count && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    private fun supervisor(
        script: String,
        policy: RestartPolicy = RestartPolicy(),
        probe: (String, Int) -> Boolean = { _, _ -> true },
    ): InstanceSupervisor =
        supervisorWith(
            object : InstanceSupervisor.Launcher {
                override fun configPorts(configPath: String) = emptySet<Int>()

                override fun launch(configPath: String, apiAddress: String, apiPort: Int) =
                    ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start().also {
                        launched += it
                    }
            },
            policy,
            probe,
        )

    private fun supervisorWith(
        launcher: InstanceSupervisor.Launcher,
        policy: RestartPolicy = RestartPolicy(),
        probe: (String, Int) -> Boolean = { _, _ -> true },
    ): InstanceSupervisor =
        InstanceSupervisor(
                launcher,
                onLog = { _, line -> logs += line },
                onChange = { infos -> infos.firstOrNull()?.let { statuses += it.status } },
                policy = policy,
                probe = probe,
                listener = listener,
            )
            .also { supervisor = it }

    @After
    fun tearDown() {
        supervisor?.close()
        launched.forEach { it.destroyForcibly() }
    }

    private fun InstanceSupervisor.awaitInstance(
        timeoutMs: Long = 5_000,
        condition: (XrayInstanceInfo?) -> Boolean,
    ): XrayInstanceInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val info = snapshot().firstOrNull()
            if (condition(info)) return info
            Thread.sleep(20)
        }
        throw AssertionError("timed out; last state ${snapshot()}")
    }

    @Test
    fun runsWhenTheApiAnswers_andStopKillsIt() {
        val s = supervisor("echo started; exec sleep 30")

        s.start("/configs/a.json", isPrimaryVpn = true)
        val info = s.awaitInstance { it?.status == InstanceStatus.RUNNING }!!

        assertEquals("a", info.configName)
        assertTrue(info.isPrimaryVpn)
        assertTrue(info.apiAddress.startsWith("127."))
        assertTrue(info.apiPort in 10000..65535)

        // Its output reaches the log.
        while (!logs.contains("started")) Thread.sleep(20)

        s.stop("/configs/a.json")
        s.awaitInstance { it == null }
        assertFalse(launched.single().isAlive)
    }

    @Test
    fun aStartupFailureIsAnErrorWithXraysReason_notRestarted() {
        val s = supervisor("echo 'Failed to start: port 10808 in use'; echo bye; exit 23")

        s.start("/configs/a.json", isPrimaryVpn = false)
        val info = s.awaitInstance { it?.status == InstanceStatus.ERROR }!!

        assertEquals(
            "Xray exited with code 23: Failed to start: port 10808 in use",
            info.errorMessage,
        )
        assertEquals(0, info.restarts)
        assertEquals(1, launched.size)
    }

    @Test
    fun aCrashAfterRunningIsRestarted_untilTheLimit() {
        val policy = RestartPolicy(stableAfterMs = 100, maxRestarts = 2, baseDelayMs = 10)
        val s = supervisor("sleep 0.3; echo 'panic: boom'; exit 2", policy)

        s.start("/configs/a.json", isPrimaryVpn = false)
        val info = s.awaitInstance(timeoutMs = 10_000) { it?.status == InstanceStatus.ERROR }!!

        assertEquals(3, launched.size)
        assertEquals(2, info.restarts)
        assertEquals("Xray exited with code 2: panic: boom", info.errorMessage)
        assertTrue(statuses.contains(InstanceStatus.RESTARTING))
        assertTrue(logs.any { it.startsWith("Restarting in") })
    }

    @Test
    fun anApiThatNeverAnswersTimesOut_andTheProcessIsKilled() {
        val policy = RestartPolicy(startupTimeoutMs = 300)
        val s = supervisor("exec sleep 30", policy, probe = { _, _ -> false })

        s.start("/configs/a.json", isPrimaryVpn = false)
        val info = s.awaitInstance { it?.status == InstanceStatus.ERROR }!!

        assertEquals("Xray did not open its API port within 0 s", info.errorMessage)
        assertFalse(launched.single().isAlive)
    }

    @Test
    fun startingAgainReplacesTheRunningProcess() {
        val s = supervisor("exec sleep 30")

        s.start("/configs/a.json", isPrimaryVpn = false)
        s.awaitInstance { it?.status == InstanceStatus.RUNNING }
        s.start("/configs/a.json", isPrimaryVpn = true)
        s.awaitInstance { it?.isPrimaryVpn == true && it.status == InstanceStatus.RUNNING }

        assertEquals(2, launched.size)
        assertFalse(launched[0].isAlive)
        assertTrue(launched[1].isAlive)
        assertEquals(1, s.snapshot().size)
    }

    @Test
    fun closeStopsEverything() {
        val s = supervisor("exec sleep 30")
        s.start("/configs/a.json", isPrimaryVpn = true)
        s.start("/configs/b.json", isPrimaryVpn = false)
        while (launched.size < 2 || s.snapshot().any { it.status != InstanceStatus.RUNNING }) {
            Thread.sleep(20)
        }

        s.close()

        assertTrue(s.snapshot().isEmpty())
        assertTrue(launched.none { it.isAlive })
        s.start("/configs/c.json", isPrimaryVpn = false)
        Thread.sleep(200)
        assertEquals(2, launched.size)
    }

    @Test
    fun stoppingWhileXrayOutputIsBeingReadDoesNotThrow() {
        // Android reports a stream closed by destroy() as InterruptedIOException, in the thread
        // blocked reading the process's output. Stopping used to let that escape and kill the
        // service process.
        val stream = BlockingInputStream()
        val process = FakeProcess(stream)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> failures += e }
        try {
            val s =
                supervisorWith(
                    object : InstanceSupervisor.Launcher {
                        override fun configPorts(configPath: String) = emptySet<Int>()

                        override fun launch(
                            configPath: String,
                            apiAddress: String,
                            apiPort: Int,
                        ) = process.also { launched += it }
                    }
                )
            s.start("/configs/a.json", isPrimaryVpn = true)
            s.awaitInstance { it?.status == InstanceStatus.RUNNING }
            assertTrue("log reader never started", stream.awaitReading())

            s.stop("/configs/a.json")
            s.awaitInstance { it == null }
            Thread.sleep(200)

            assertTrue("uncaught exception: $failures", failures.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Test
    fun listener_aRunThatIsStopped() {
        val s = supervisor("exec sleep 30")
        s.start("/configs/a.json", isPrimaryVpn = true)
        s.awaitInstance { it?.status == InstanceStatus.RUNNING }

        s.stop("/configs/a.json")
        awaitRuns(3)

        assertEquals("started a", runs[0])
        assertEquals("ready a", runs[1])
        assertTrue(runs[2], runs[2].startsWith("ended a STOPPED"))
        assertEquals(3, runs.size)
    }

    @Test
    fun listener_aRunThatFailsAtStartup() {
        val s = supervisor("exit 23")
        s.start("/configs/a.json", isPrimaryVpn = false)
        s.awaitInstance { it?.status == InstanceStatus.ERROR }
        awaitRuns(3)

        assertEquals("ended a FAILED 23", runs.last())
    }

    @Test
    fun listener_crashesThenGivingUp() {
        val policy = RestartPolicy(stableAfterMs = 100, maxRestarts = 1, baseDelayMs = 10)
        val s = supervisor("sleep 0.3; exit 2", policy)
        s.start("/configs/a.json", isPrimaryVpn = false)
        s.awaitInstance(timeoutMs = 10_000) { it?.status == InstanceStatus.ERROR }
        awaitRuns(6)

        assertEquals(
            listOf("ended a CRASHED 2", "ended a FAILED 2"),
            runs.filter { it.startsWith("ended") },
        )
    }

    @Test
    fun listener_aConfigThatCannotBeReadIsAFailedRun() {
        val s =
            supervisorWith(
                object : InstanceSupervisor.Launcher {
                    override fun configPorts(configPath: String): Set<Int> =
                        throw IOException("no such file")

                    override fun launch(configPath: String, apiAddress: String, apiPort: Int) =
                        throw AssertionError("not launched")
                }
            )
        s.start("/configs/a.json", isPrimaryVpn = false)
        s.awaitInstance { it?.status == InstanceStatus.ERROR }
        awaitRuns(2)

        assertEquals(listOf("started a", "ended a FAILED null"), runs)
    }

    @Test
    fun listener_aTimeout() {
        val s =
            supervisor("exec sleep 30", RestartPolicy(startupTimeoutMs = 300), { _, _ -> false })
        s.start("/configs/a.json", isPrimaryVpn = false)
        s.awaitInstance { it?.status == InstanceStatus.ERROR }
        awaitRuns(2)

        assertTrue(runs.last(), runs.last().startsWith("ended a TIMEOUT"))
    }

    @Test
    fun restartPolicy() {
        val policy = RestartPolicy(stableAfterMs = 5_000, resetAfterMs = 60_000, maxRestarts = 3)

        assertNull(policy.restartsAfter(healthyMs = 0, restarts = 0))
        assertNull(policy.restartsAfter(healthyMs = 4_999, restarts = 0))
        assertEquals(1, policy.restartsAfter(healthyMs = 5_000, restarts = 0))
        assertEquals(3, policy.restartsAfter(healthyMs = 10_000, restarts = 2))
        assertNull(policy.restartsAfter(healthyMs = 10_000, restarts = 3))
        // A long healthy run earns a fresh start.
        assertEquals(1, policy.restartsAfter(healthyMs = 60_000, restarts = 3))

        assertEquals(1_000, policy.delayMs(1))
        assertEquals(2_000, policy.delayMs(2))
        assertEquals(16_000, policy.delayMs(5))
        assertEquals(30_000, policy.delayMs(6))
        assertEquals(30_000, policy.delayMs(100))
    }

    @Test
    fun describeExit() {
        assertEquals("Xray exited with code 1", InstanceSupervisor.describeExit(1, emptyList()))
        assertEquals(
            "Xray exited with code 1: last",
            InstanceSupervisor.describeExit(1, listOf("first", "last", " ")),
        )
        assertEquals(
            "Xray exited with code 1: [Error] failed to dial",
            InstanceSupervisor.describeExit(1, listOf("[Error] failed to dial", "shutting down")),
        )
        // Traffic logging is not a reason.
        assertEquals(
            "Xray exited with code 0",
            InstanceSupervisor.describeExit(
                0,
                listOf(
                    "2026/10/24 14:21:23 from tcp:127.0.0.1:1 accepted tcp:1.2.3.4:443 [proxy]",
                    "2026/10/24 14:21:24 [Info] app/dispatcher: taking detour",
                ),
            ),
        )
    }

    /** Blocks in read() until closed, then fails the way Android does when a stream is closed. */
    private class BlockingInputStream : InputStream() {
        private val reading = CountDownLatch(1)
        private val closed = CountDownLatch(1)

        fun awaitReading(): Boolean = reading.await(5, TimeUnit.SECONDS)

        fun closeStream() = closed.countDown()

        override fun read(): Int {
            reading.countDown()
            closed.await()
            throw IOException("read interrupted by close() on another thread")
        }
    }

    private class FakeProcess(private val stream: BlockingInputStream) : Process() {
        @Volatile private var destroyed = false

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = stream

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            while (!destroyed) Thread.sleep(10)
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitFor()
            return true
        }

        override fun exitValue(): Int = if (destroyed) 0 else throw IllegalThreadStateException()

        override fun destroy() {
            destroyed = true
            stream.closeStream()
        }

        override fun isAlive(): Boolean = !destroyed
    }
}
