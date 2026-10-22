// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.viewmodel

import com.simplexray.an.core.XrayInstanceInfo

data class TrafficState(
    val uplink: Long,
    val downlink: Long,
)

/** One instance's figures from its stats API. Rates are bytes per second since the last poll. */
data class InstanceStats(
    val uplink: Long = 0,
    val downlink: Long = 0,
    val uplinkRate: Long = 0,
    val downlinkRate: Long = 0,
    val numGoroutine: Int = 0,
    val alloc: Long = 0,
    val uptime: Int = 0,
)

/** A supervised instance and, while its API answers, its stats. */
data class InstanceState(
    val info: XrayInstanceInfo,
    val stats: InstanceStats? = null,
)

/**
 * Totals over every instance whose API answers; uptime is the primary instance's, since that is how
 * long the VPN has been carried by the current core.
 */
data class CoreStatsState(
    val uplink: Long = 0,
    val downlink: Long = 0,
    val uplinkRate: Long = 0,
    val downlinkRate: Long = 0,
    val numGoroutine: Int = 0,
    val alloc: Long = 0,
    val uptime: Int = 0,
) {
    companion object {
        fun of(instances: List<InstanceState>): CoreStatsState {
            val stats = instances.mapNotNull { it.stats }
            val primary = instances.firstOrNull { it.info.isPrimaryVpn }?.stats
            return CoreStatsState(
                uplink = stats.sumOf { it.uplink },
                downlink = stats.sumOf { it.downlink },
                uplinkRate = stats.sumOf { it.uplinkRate },
                downlinkRate = stats.sumOf { it.downlinkRate },
                numGoroutine = stats.sumOf { it.numGoroutine },
                alloc = stats.sumOf { it.alloc },
                uptime = (primary ?: stats.firstOrNull())?.uptime ?: 0,
            )
        }
    }
}

/** Bytes per second between two readings of a counter; 0 if it went backwards (a restart). */
internal fun ratePerSecond(previous: Long, current: Long, elapsedMs: Long): Long =
    if (elapsedMs <= 0 || current < previous) 0 else (current - previous) * 1000 / elapsedMs
