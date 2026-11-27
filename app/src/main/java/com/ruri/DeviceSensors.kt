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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Local device and time snapshot; no screen text or message contents are collected. */
object DeviceSensors {

    /**
     * Wall-clock context for the prompt.
     *
     * She had the battery level but not the hour, so she could not tell morning from midnight
     * and wrote "доброе утро" at 3am. The formatter is created per call on purpose: the phone's
     * zone can change and a cached instance would keep the old one.
     */
    fun timeContext(now: Long = System.currentTimeMillis()): String {
        val zone = TimeZone.getDefault()
        val time = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zone }.format(Date(now))
        val date = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("ru")).apply {
            timeZone = zone
        }.format(Date(now))
        val dayPart = when (Calendar.getInstance(zone).apply { timeInMillis = now }
            .get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "утро"
            in 12..16 -> "день"
            in 17..22 -> "вечер"
            else -> "ночь"
        }
        return "Сейчас на телефоне $time ($dayPart), $date. " +
            "Опирайся на это: не поздравляй с утром ночью и не путай день с вечером."
    }

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
