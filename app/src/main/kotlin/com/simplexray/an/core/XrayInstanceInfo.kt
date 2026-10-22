// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.core

enum class InstanceStatus {
    /** Launched; its API port is not answering yet. */
    STARTING,

    /** Its API port answers. */
    RUNNING,

    /** Crashed after running; waiting to be started again. */
    RESTARTING,

    /** Failed to start, or crashed too often. errorMessage says why. */
    ERROR,
}

/** One supervised Xray process, as published to the UI through InstanceRegistry. */
data class XrayInstanceInfo(
    val configPath: String,
    val configName: String,
    val apiAddress: String = "127.0.0.1",
    val apiPort: Int = 0,
    val isPrimaryVpn: Boolean = false,
    val status: InstanceStatus = InstanceStatus.STARTING,
    val errorMessage: String? = null,
    /** Automatic restarts since the instance last ran stably. */
    val restarts: Int = 0,
) {
    /** Starting, running or about to restart: not stopped and not given up on. */
    val isActive: Boolean
        get() = status != InstanceStatus.ERROR
}
