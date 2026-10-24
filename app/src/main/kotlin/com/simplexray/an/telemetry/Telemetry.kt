// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.telemetry

import android.content.Context
import android.util.Log
import com.simplexray.an.prefs.Preferences
import java.io.File

/** Where telemetry lives, and how the app's own process records events. */
object Telemetry {
    private const val TAG = "Telemetry"

    /** The process that writes, which names its files: the service's or the app's. */
    const val SERVICE = "svc"
    const val APP = "app"

    fun root(context: Context) = File(context.noBackupFilesDir, "telemetry")

    fun store(context: Context, process: String) = TelemetryStore(root(context), process)

    /**
     * Records an event from the app's process (imports, permission answers, connectivity tests).
     * The service records its own through its [Recorder].
     */
    fun event(
        context: Context,
        level: Level,
        category: Category,
        type: String,
        message: String,
        attrs: Map<String, String> = emptyMap(),
    ) {
        if (!Preferences(context).historyEnabled) return
        runCatching {
            store(context, APP)
                .append(
                    Kind.EVENTS,
                    Event(System.currentTimeMillis(), level, category, type, message, null, attrs)
                        .toJson(),
                )
        }
            .onFailure { Log.w(TAG, "Could not record $type", it) }
    }
}
