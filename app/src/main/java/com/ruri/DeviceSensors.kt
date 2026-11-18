package com.ruri
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Local device and time snapshot; no screen text or message contents are collected. */
object DeviceSensors {
    fun snapshot(context: Context, now: Long): JSONObject {
        val zone = TimeZone.getDefault()
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply { timeZone = zone }
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING || batteryStatus == BatteryManager.BATTERY_STATUS_FULL
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val capabilities = if (network != null) connectivity.getNetworkCapabilities(network) else null
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val transport = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            capabilities != null -> "other"
            else -> "offline"
        }
        return JSONObject()
            .put("time_unix_ms", now)
            .put("local_time", formatter.format(Date(now)))
            .put("timezone", zone.id)
            .put("screen_interactive", power.isInteractive)
            .put("charging", charging)
            .put("network_transport", transport)
            .put("network_validated", validated)
            .put("usage_access_granted", UsageAccessSensor.hasAccess(context))
            .put("foreground_app_package", UsageAccessSensor.recentForegroundPackage(context) ?: JSONObject.NULL)
            .put("privacy", "local")
    }
}
