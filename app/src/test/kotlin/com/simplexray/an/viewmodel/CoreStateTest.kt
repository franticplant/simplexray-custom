// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.viewmodel

import com.simplexray.an.core.InstanceStatus
import com.simplexray.an.core.XrayInstanceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class CoreStateTest {
    @Test
    fun ratePerSecond() {
        assertEquals(1_000, ratePerSecond(previous = 0, current = 500, elapsedMs = 500))
        assertEquals(0, ratePerSecond(previous = 10, current = 10, elapsedMs = 1_000))
        // Counters went backwards: the core restarted.
        assertEquals(0, ratePerSecond(previous = 900, current = 100, elapsedMs = 1_000))
        assertEquals(0, ratePerSecond(previous = 0, current = 100, elapsedMs = 0))
    }

    @Test
    fun totalsSumEveryInstanceWithStats_uptimeIsThePrimarys() {
        fun state(primary: Boolean, stats: InstanceStats?) =
            InstanceState(
                XrayInstanceInfo(
                    "/c/$primary",
                    "c",
                    isPrimaryVpn = primary,
                    status = InstanceStatus.RUNNING,
                ),
                stats,
            )
        val total =
            CoreStatsState.of(
                listOf(
                    state(
                        false,
                        InstanceStats(
                            uplink = 1,
                            downlink = 2,
                            uplinkRate = 3,
                            downlinkRate = 4,
                            numGoroutine = 5,
                            alloc = 6,
                            uptime = 7,
                        ),
                    ),
                    state(
                        true,
                        InstanceStats(
                            uplink = 10,
                            downlink = 20,
                            uplinkRate = 30,
                            downlinkRate = 40,
                            numGoroutine = 50,
                            alloc = 60,
                            uptime = 70,
                        ),
                    ),
                    state(false, null),
                )
            )

        assertEquals(CoreStatsState(11, 22, 33, 44, 55, 66, 70), total)
        assertEquals(CoreStatsState(), CoreStatsState.of(emptyList()))
    }
}
