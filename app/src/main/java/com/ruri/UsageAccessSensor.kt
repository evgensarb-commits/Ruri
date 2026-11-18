package com.ruri
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/** Reads only the most recent foreground package after explicit Usage Access consent. */
object UsageAccessSensor {
    fun hasAccess(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    fun recentForegroundPackage(context: Context, lookbackMillis: Long = 15L * 60L * 1000L): String? {
        if (!hasAccess(context)) return null
        return try {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = manager.queryEvents((now - lookbackMillis).coerceAtLeast(0L), now)
            val event = UsageEvents.Event()
            var foreground: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val isForeground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                } else event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                val isBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                        event.eventType == UsageEvents.Event.ACTIVITY_STOPPED ||
                        event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
                } else event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
                if (isForeground) foreground = event.packageName
                if (isBackground && foreground == event.packageName) foreground = null
            }
            foreground
        } catch (_: Exception) { null }
    }
}
