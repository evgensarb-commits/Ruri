package com.ruri
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** Records coarse Android events while the visible foreground service is active. */
object DeviceEventMonitor {
    private val lock = Any()
    private var receiver: BroadcastReceiver? = null
    private var lastBatteryLevel = -1
    private var lastBatteryBand = -1

    fun register(context: Context) {
        synchronized(lock) {
            if (receiver != null) return
            val app = context.applicationContext
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
                addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
                addAction(Intent.ACTION_DEVICE_STORAGE_OK)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            val instance = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    val action = intent.action ?: return
                    if (action == Intent.ACTION_BATTERY_CHANGED) {
                        handleBattery(receiverContext, intent)
                        return
                    }
                    val type = when (action) {
                        Intent.ACTION_SCREEN_ON -> "screen_on"
                        Intent.ACTION_SCREEN_OFF -> "screen_off"
                        Intent.ACTION_USER_PRESENT -> "device_unlocked"
                        Intent.ACTION_POWER_CONNECTED -> "power_connected"
                        Intent.ACTION_POWER_DISCONNECTED -> "power_disconnected"
                        Intent.ACTION_TIME_CHANGED -> "system_time_changed"
                        Intent.ACTION_TIMEZONE_CHANGED -> "timezone_changed"
                        Intent.ACTION_BATTERY_LOW -> "battery_low"
                        Intent.ACTION_BATTERY_OKAY -> "battery_okay"
                        Intent.ACTION_DEVICE_STORAGE_LOW -> "storage_low"
                        Intent.ACTION_DEVICE_STORAGE_OK -> "storage_okay"
                        else -> return
                    }
                    record(receiverContext, type, JSONObject())
                }
            }
            try {
                ContextCompat.registerReceiver(app, instance, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                receiver = instance
            } catch (e: Exception) {
                Log.w("Ruri", "Device event monitor registration failed", e)
            }
        }
    }

    fun unregister(context: Context) {
        synchronized(lock) {
            val current = receiver ?: return
            try { context.applicationContext.unregisterReceiver(current) }
            catch (e: Exception) { Log.w("Ruri", "Device monitor unregister failed", e) }
            finally { receiver = null }
        }
    }

    private fun handleBattery(context: Context, intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val percent = (level * 100 / scale).coerceIn(0, 100)
        val band = when { percent <= 15 -> 1; percent <= 30 -> 2; percent <= 50 -> 3; else -> 4 }
        if (lastBatteryLevel >= 0 && kotlin.math.abs(percent - lastBatteryLevel) < 10 && band == lastBatteryBand) return
        lastBatteryLevel = percent
        lastBatteryBand = band
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        record(context, "battery_state_changed", JSONObject().put("percent", percent).put("charging", charging))
    }

    private fun record(context: Context, type: String, data: JSONObject) {
        try {
            FileManager.appendEvent(
                context.filesDir,
                JSONObject().put("type", type).put("source", "android_system")
                    .put("data", data).put("privacy", "local")
            )
            LifeScheduler.enqueueEvent(context, "phone_" + type)
        } catch (e: Exception) {
            Log.w("Ruri", "Could not record device event: " + type, e)
        }
    }
}
