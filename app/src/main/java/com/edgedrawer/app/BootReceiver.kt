package com.edgedrawer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PrefsManager(context)
            if (prefs.isServiceEnabled && Settings.canDrawOverlays(context)) {
                val serviceIntent = Intent(context, EdgeOverlayService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
