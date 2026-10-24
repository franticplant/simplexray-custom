// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.common

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object ConfigUtils {
    private const val TAG = "ConfigUtils"

    @Throws(JSONException::class)
    fun formatConfigContent(content: String): String {
        val jsonObject = JSONObject(content)
        jsonObject.optJSONObject("log")?.apply {
            if (has("access") && optString("access") != "none") {
                remove("access")
                Log.d(TAG, "Removed log.access")
            }
            if (has("error") && optString("error") != "none") {
                remove("error")
                Log.d(TAG, "Removed log.error")
            }
        }
        var formattedContent = jsonObject.toString(2)
        formattedContent = formattedContent.replace("\\/", "/")
        return formattedContent
    }

    /**
     * Adds a StatsService API on apiAddress:apiPort and turns on outbound traffic counters, keeping
     * whatever api, stats and policy settings the config already has.
     */
    @Throws(JSONException::class)
    fun injectStatsService(apiAddress: String, apiPort: Int, configContent: String): String {
        val jsonObject = JSONObject(configContent)

        val api = jsonObject.optJSONObject("api") ?: JSONObject()
        if (!api.has("tag")) api.put("tag", "api")
        api.put("listen", "$apiAddress:$apiPort")
        val services = api.optJSONArray("services") ?: JSONArray()
        if ((0 until services.length()).none { services.optString(it) == "StatsService" }) {
            services.put("StatsService")
        }
        api.put("services", services)
        jsonObject.put("api", api)

        if (jsonObject.optJSONObject("stats") == null) jsonObject.put("stats", JSONObject())
        tagOutbounds(jsonObject)

        val policy = jsonObject.optJSONObject("policy") ?: JSONObject()
        val system = policy.optJSONObject("system") ?: JSONObject()
        system.put("statsOutboundUplink", true)
        system.put("statsOutboundDownlink", true)
        policy.put("system", system)
        jsonObject.put("policy", policy)

        return jsonObject.toString(2)
    }

    /**
     * Gives every untagged outbound a tag ("outbound-<index>"). Xray keeps traffic counters only
     * for tagged outbounds, and configs made from share links have none. Routing is unaffected: no
     * rule can name an outbound that has no tag.
     */
    fun tagOutbounds(config: JSONObject): JSONObject {
        val outbounds = config.optJSONArray("outbounds") ?: return config
        val taken =
            (0 until outbounds.length())
                .mapNotNull {
                    outbounds.optJSONObject(it)?.optString("tag")?.takeIf(String::isNotEmpty)
                }
                .toMutableSet()
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            if (outbound.optString("tag").isNotEmpty()) continue
            var tag = "outbound-$i"
            while (tag in taken) tag += "_"
            taken += tag
            outbound.put("tag", tag)
        }
        return config
    }

    fun extractPortsFromJson(jsonContent: String): Set<Int> {
        val ports = mutableSetOf<Int>()
        try {
            val jsonObject = JSONObject(jsonContent)
            extractPortsRecursive(jsonObject, ports)
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing JSON for port extraction", e)
        }
        Log.d(TAG, "Extracted ports: $ports")
        return ports
    }

    private fun extractPortsRecursive(jsonObject: JSONObject, ports: MutableSet<Int>) {
        for (key in jsonObject.keys()) {
            when (val value = jsonObject.get(key)) {
                is Int -> {
                    if (value in 1..65535) {
                        ports.add(value)
                    }
                }

                is JSONObject -> {
                    extractPortsRecursive(value, ports)
                }

                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val item = value.get(i)
                        if (item is JSONObject) {
                            extractPortsRecursive(item, ports)
                        }
                    }
                }
            }
        }
    }
}
