// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.core

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The supervised Xray instances, shared across processes. InstanceSupervisor runs the cores in the
 * :native service process, and TProxyService publishes their state here; the UI process reads it
 * back, since it cannot see the manager's in-memory state. The file is replaced atomically, so a
 * reader never sees a partial write.
 */
object InstanceRegistry {
    private const val TAG = "InstanceRegistry"
    private const val FILE_NAME = "instances.json"

    fun publish(context: Context, instances: List<XrayInstanceInfo>) {
        val file = File(context.filesDir, FILE_NAME)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        try {
            tmp.writeText(encode(instances))
            if (!tmp.renameTo(file)) Log.e(TAG, "Failed to replace $file")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish instances", e)
        }
    }

    fun read(context: Context): List<XrayInstanceInfo> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return emptyList()
        return try {
            decode(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read instances", e)
            emptyList()
        }
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    internal fun encode(instances: List<XrayInstanceInfo>): String {
        val array = JSONArray()
        instances.forEach { info ->
            array.put(
                JSONObject()
                    .put("configPath", info.configPath)
                    .put("configName", info.configName)
                    .put("apiAddress", info.apiAddress)
                    .put("apiPort", info.apiPort)
                    .put("isPrimaryVpn", info.isPrimaryVpn)
                    .put("status", info.status.name)
                    .put("errorMessage", info.errorMessage ?: JSONObject.NULL)
                    .put("restarts", info.restarts)
            )
        }
        return array.toString()
    }

    @Throws(JSONException::class)
    internal fun decode(json: String): List<XrayInstanceInfo> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            XrayInstanceInfo(
                configPath = o.getString("configPath"),
                configName = o.getString("configName"),
                apiAddress = o.getString("apiAddress"),
                apiPort = o.getInt("apiPort"),
                isPrimaryVpn = o.getBoolean("isPrimaryVpn"),
                status =
                    runCatching { InstanceStatus.valueOf(o.getString("status")) }
                        .getOrDefault(InstanceStatus.ERROR),
                errorMessage = if (o.isNull("errorMessage")) null else o.getString("errorMessage"),
                restarts = o.optInt("restarts"),
            )
        }
    }
}
