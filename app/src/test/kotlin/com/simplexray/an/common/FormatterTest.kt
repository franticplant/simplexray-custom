// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.common

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FormatterTest {
    private lateinit var defaultLocale: Locale

    @Before
    fun setUp() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun formatBytes_picksLargestWholeUnit() {
        assertEquals("0B", formatBytes(0))
        assertEquals("512.00B", formatBytes(512))
        assertEquals("1.00KB", formatBytes(1024))
        assertEquals("1.50MB", formatBytes(1024L * 1024 * 3 / 2))
        assertEquals("2.00GB", formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun formatBytes_capsAtTerabytes() {
        assertEquals("1024.00TB", formatBytes(1024L * 1024 * 1024 * 1024 * 1024))
    }

    @Test
    fun formatNumber_usesSiSuffixes() {
        assertEquals("999", formatNumber(999))
        assertEquals("1.0K", formatNumber(1000))
        assertEquals("1.5M", formatNumber(1_500_000))
        assertEquals("2.0G", formatNumber(2_000_000_000))
    }

    @Test
    fun formatUptime_isHoursMinutesSeconds() {
        assertEquals("00:00:00", formatUptime(0))
        assertEquals("01:01:01", formatUptime(3661))
        assertEquals("100:00:00", formatUptime(360_000))
        assertEquals("N/A", formatUptime(-1))
    }
}
