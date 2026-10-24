// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import com.simplexray.an.activity.MainActivity
import com.simplexray.an.prefs.Preferences

class QuickTileService : TileService() {

    private val broadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    TProxyService.ACTION_START -> updateTileState(true)
                    TProxyService.ACTION_STOP -> updateTileState(false)
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "QuickTileService created.")
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "QuickTileService started listening.")

        IntentFilter()
            .apply {
                addAction(TProxyService.ACTION_START)
                addAction(TProxyService.ACTION_STOP)
            }
            .also { filter ->
                ContextCompat.registerReceiver(
                    this,
                    broadcastReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }

        updateTileState(isVpnServiceRunning(this, TProxyService::class.java))
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(TAG, "QuickTileService stopped listening.")
        try {
            unregisterReceiver(broadcastReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver not registered", e)
        }
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "QuickTileService clicked.")

        qsTile.run {
            if (state == Tile.STATE_INACTIVE) {
                val prefs = Preferences(this@QuickTileService)
                val needsApp =
                    prefs.selectedConfigPath == null ||
                        (!prefs.disableVpn && VpnService.prepare(this@QuickTileService) != null)
                if (needsApp) {
                    // No config to run, or the VPN needs the user's consent: both happen in the
                    // app.
                    Log.w(TAG, "Not ready to connect from the tile; opening the app.")
                    openApp()
                    return
                }
                startTProxyService(TProxyService.ACTION_START)
            } else {
                startTProxyService(TProxyService.ACTION_DISCONNECT)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "QuickTileService destroyed.")
    }

    // The Intent overload is only used below API 34, where the PendingIntent one does not exist.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }

    private fun updateTileState(isActive: Boolean) {
        qsTile.apply {
            state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @Suppress("DEPRECATION")
    private fun isVpnServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.getRunningServices(Int.MAX_VALUE).any { service ->
            serviceClass.name == service.service.className
        }
    }

    private fun startTProxyService(action: String) {
        Intent(this, TProxyService::class.java)
            .apply {
                this.action = action
            }
            .also { intent ->
                startService(intent)
            }
    }

    companion object {
        private const val TAG = "QuickTileService"
    }
}
