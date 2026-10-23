// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
                    restarts = 2,
                ),
            )

        assertEquals(instances, InstanceRegistry.decode(InstanceRegistry.encode(instances)))
    }

    @Test
    fun decode_unknownStatusIsAnError_missingRestartsIsZero() {
        // As an older or newer version of the service might write it.
        val json =
            """[{"configPath":"/a.json","configName":"a","apiAddress":"127.0.0.1","apiPort":1,""" +
                """"isPrimaryVpn":false,"status":"PAUSED","errorMessage":null}]"""

        val info = InstanceRegistry.decode(json).single()

        assertEquals(InstanceStatus.ERROR, info.status)
        assertEquals(0, info.restarts)
    }

    @Test
    fun isRegistry_tellsTheRegistryFromAConfigOfTheSameName() {
        assertTrue(InstanceRegistry.isRegistry(InstanceRegistry.encode(emptyList())))
        assertTrue(InstanceRegistry.isRegistry("\n [{\"configPath\":\"/a.json\"}]"))
        assertFalse(InstanceRegistry.isRegistry("{\"outbounds\":[]}"))
        assertFalse(InstanceRegistry.isRegistry(""))
    }
}
