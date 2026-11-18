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
            val event = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("type", "phone_notification")
                .put("package", sbn.packageName)
                .put("title", title.take(180))
                .put("text", text.take(500))
            val path = "memory/L0_raw.jsonl"
            val old = FileManager.read(filesDir, path)
            FileManager.write(filesDir, path, if (old.isBlank()) event.toString() else old.trimEnd() + "\n" + event)
        } catch (e: Exception) {
            Log.w("Ruri", "Could not record phone notification", e)
        }
    }
}