package com.ruri

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RuriService::class.java)
            )
        } catch (e: Exception) {
            Log.e("Ruri", "Unable to restart service after boot", e)
        }
    }
}