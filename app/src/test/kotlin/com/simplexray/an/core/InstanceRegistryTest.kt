// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.core

import org.junit.Assert.assertEquals
import org.junit.Test

class InstanceRegistryTest {
    @Test
    fun encodeDecode_roundTripsWhatTheUiNeeds() {
        val instances =
            listOf(
                XrayInstanceInfo(
                    configPath = "/data/files/a.json",
                    configName = "a",
                    apiAddress = "127.1.2.3",
                    apiPort = 14222,
                    isPrimaryVpn = true,
                    status = InstanceStatus.RUNNING,
                ),
                XrayInstanceInfo(
                    configPath = "/data/files/b.json",
                    configName = "b",
                    apiAddress = "127.4.5.6",
                    apiPort = 20000,
                    status = InstanceStatus.ERROR,
                    errorMessage = "exec failed",
                ),
            )

        assertEquals(instances, InstanceRegistry.decode(InstanceRegistry.encode(instances)))
    }

    @Test
    fun encode_leavesOutStats() {
        val info =
            XrayInstanceInfo(configPath = "/a.json", configName = "a", uplink = 5, downlink = 7)

        val decoded = InstanceRegistry.decode(InstanceRegistry.encode(listOf(info))).single()

        assertEquals(0L, decoded.uplink)
        assertEquals(0L, decoded.downlink)
    }

    @Test
    fun decode_unknownStatusIsStopped() {
        val json =
            """[{"configPath":"/a.json","configName":"a","apiAddress":"127.0.0.1","apiPort":1,""" +
                """"isPrimaryVpn":false,"status":"PAUSED","errorMessage":null}]"""

        assertEquals(InstanceStatus.STOPPED, InstanceRegistry.decode(json).single().status)
    }
}
