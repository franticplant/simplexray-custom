// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.data.source

import java.io.File
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFileManagerTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun format_replacesXraysTimestampWithOurs() {
        val line =
            LogFileManager.format(
                "Home",
                "2026/10/24 16:39:08.699431 [Warning] core: Xray 26.2.6 started",
                1_792_850_354_043,
                utc,
            )

        assertEquals("13:59:14.043 [Home] [Warning] core: Xray 26.2.6 started", line)
    }

    @Test
    fun format_keepsLinesWithoutATimestamp() {
        assertEquals(
            "00:00:00.000 [A] Restarting in 2 s: boom",
            LogFileManager.format("A", "Restarting in 2 s: boom", 0, utc),
        )
    }

    @Test
    fun configOf_isTheBracketedNameAfterTheTime() {
        assertEquals("My Server", LogFileManager.configOf("12:00:00.000 [My Server] [Info] x"))
        assertNull(LogFileManager.configOf("no brackets here"))
    }

    @Test
    fun isProblem() {
        assertTrue(LogFileManager.isProblem("12:00:00.000 [A] [Warning] something"))
        assertTrue(LogFileManager.isProblem("12:00:00.000 [A] failed to dial"))
        assertTrue(LogFileManager.isProblem("12:00:00.000 [A] panic: boom"))
        assertFalse(LogFileManager.isProblem("12:00:00.000 [A] [Info] accepted tcp:x:443"))
    }

    @Test
    fun startedAt_comesFromTheFileName() {
        assertEquals(1_792_850_354_000, LogFileManager.startedAt(File("20261024-135914_abc.txt")))
        assertNull(LogFileManager.startedAt(File("garbage.txt")))
    }
}
