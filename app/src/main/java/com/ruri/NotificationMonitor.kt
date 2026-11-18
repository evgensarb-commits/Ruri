package com.ruri

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

class NotificationMonitor : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        try {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = NotificationTextExtractor.extract(
                extras,
                excludedKeys = setOf(Notification.EXTRA_TITLE)
            )
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
            LifeScheduler.enqueueEvent(applicationContext, "phone_notification_removed")
        } catch (e: Exception) {
            Log.w("Ruri", "Could not record notification removal", e)
        }
    }
}
