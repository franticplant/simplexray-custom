// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.core

import android.content.Context
import com.simplexray.an.common.ConfigUtils
import java.io.File

/** Starts the bundled Xray binary on a config, with its stats API added. */
class XrayLauncher(private val context: Context) : InstanceSupervisor.Launcher {
    override fun configPorts(configPath: String): Set<Int> =
        ConfigUtils.extractPortsFromJson(File(configPath).readText())

    override fun launch(configPath: String, apiAddress: String, apiPort: Int): Process {
        val config =
            ConfigUtils.injectStatsService(apiAddress, apiPort, File(configPath).readText())
        val filesDir = context.filesDir
        // libxray.so is an executable, packaged as a library so it is extracted and executable.
        val builder =
            ProcessBuilder("${context.applicationInfo.nativeLibraryDir}/libxray.so")
                .directory(filesDir)
                .redirectErrorStream(true)
        builder.environment()["XRAY_LOCATION_ASSET"] = filesDir.path
        val process = builder.start()
        // Xray reads its config from stdin.
        process.outputStream.use { it.write(config.toByteArray()) }
        return process
    }
}
