package com.simplexray.an.core

enum class InstanceStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

data class XrayInstanceInfo(
    val configPath: String,
    val configName: String,
    val apiAddress: String = "127.0.0.1",
    val apiPort: Int = 0,
    val isPrimaryVpn: Boolean = false,
    val status: InstanceStatus = InstanceStatus.STOPPED,
    val uplink: Long = 0,
    val downlink: Long = 0,
    val numGoroutine: Int = 0,
    val alloc: Long = 0,
    val uptime: Int = 0,
    val errorMessage: String? = null
)
