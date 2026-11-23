package com.ruri

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

class NotificationMonitor : NotificationListenerService() {
    private val lastContentByKey = LinkedHashMap<String, Int>()

    internal fun shouldRecordNotification(key: String, fingerprint: Int): Boolean = synchronized(lastContentByKey) {
        if (lastContentByKey[key] == fingerprint) return@synchronized false
        lastContentByKey[key] = fingerprint
        while (lastContentByKey.size > 256) {
            val oldest = lastContentByKey.keys.firstOrNull() ?: break
            lastContentByKey.remove(oldest)
        }
        true
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        try {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim().take(2000)
            val text = NotificationTextExtractor.extract(
                extras,
                excludedKeys = setOf(Notification.EXTRA_TITLE)
            )
            val fingerprint = (title + "\u0000" + text).hashCode()
            if (!shouldRecordNotification(sbn.key, fingerprint)) return
            FileManager.appendEvent(
                filesDir,
                JSONObject()
                    .put("type", "phone_notification")
                    .put("source", "notification_listener")
                    .put("notification_key", sbn.key)
                    .put("notification_id", sbn.id)
                    .put("package", sbn.packageName)
                    .put("title", title)
                    .put("text", text)
            )
            LifeScheduler.enqueueEvent(applicationContext, "phone_notification")
        } catch (e: Exception) {
            Log.w("Ruri", "Could not record phone notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        synchronized(lastContentByKey) { lastContentByKey.remove(sbn.key) }
        try {
            FileManager.appendEvent(
                filesDir,
                JSONObject()
                    .put("type", "phone_notification_removed")
                    .put("source", "notification_listener")
                    .put("notification_key", sbn.key)
                    .put("notification_id", sbn.id)
                    .put("package", sbn.packageName)
            )
            // A removed notification is kept in L0 for history, but does not trigger a model cycle.
        } catch (e: Exception) {
            Log.w("Ruri", "Could not record notification removal", e)
        }
    }
}
