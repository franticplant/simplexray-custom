// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.common

import kotlin.math.ln
import kotlin.math.pow

fun formatBytes(bytes: Long): String {
    if (bytes == 0L) return "0B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.size - 1)
    return "%.2f%s".format(bytes / 1024.0.pow(exp), units[exp])
}

fun formatNumber(count: Long): String {
    if (count < 1000) return count.toString()
    val suffix = listOf('K', 'M', 'G', 'T', 'P', 'E')
    val exp = (ln(count.toDouble()) / ln(1000.0)).toInt().coerceAtMost(suffix.size)
    return "%.1f%c".format(count / 1000.0.pow(exp), suffix[exp - 1])
}

fun formatUptime(seconds: Int): String =
    when {
        seconds < 0 -> "N/A"
        else -> {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            val secs = seconds % 60
            "%02d:%02d:%02d".format(hours, minutes, secs)
        }
    }

/** A duration as its two largest units: "45s", "12m 5s", "3h 20m", "2d 4h". */
fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val d = s / 86_400
    val h = s % 86_400 / 3600
    val m = s % 3600 / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s % 60}s"
        else -> "${s}s"
    }
}
