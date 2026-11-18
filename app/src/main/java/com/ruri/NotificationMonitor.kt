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
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            FileManager.appendEvent(
                filesDir,
                JSONObject()
                    .put("type", "phone_notification")
                    .put("source", "notification_listener")
                    .put("notification_key", sbn.key)
                    .put("notification_id", sbn.id)
                    .put("package", sbn.packageName)
                    .put("title", title.take(180))
                    .put("text", text.take(500))
            )
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
        } catch (e: Exception) {
            Log.w("Ruri", "Could not record notification removal", e)
        }
    }
}
