// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.telemetry

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Records the underlying network changing (Wi-Fi to mobile data, lost, back), which explains many
 * drops. The VPN's own network is not an underlying network and is ignored.
 */
class NetworkWatcher(context: Context, private val recorder: Recorder) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var last: String? = null

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                val name = describe(caps)
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val key = "$name/$validated"
                if (key == last) return
                last = key
                recorder.event(
                    if (validated) Level.INFO else Level.WARN,
                    Category.NETWORK,
                    "network.change",
                    if (validated) "Network: $name" else "Network: $name (no internet access)",
                    mapOf(
                        "transport" to name,
                        "validated" to validated.toString(),
                        "metered" to
                            (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                                .toString(),
                    ),
                )
            }

            override fun onLost(network: Network) {
                if (last == null) return
                last = null
                recorder.event(Level.WARN, Category.NETWORK, "network.lost", "Network lost")
            }
        }

    fun start() {
        runCatching { connectivity?.registerDefaultNetworkCallback(callback) }
    }

    fun stop() {
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
    }

    private fun describe(caps: NetworkCapabilities): String =
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "other"
        }
}
