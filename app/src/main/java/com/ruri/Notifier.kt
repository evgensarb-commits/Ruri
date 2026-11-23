package com.ruri

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object Notifier {
    const val THOUGHT_CHANNEL_ID = "ruri_thoughts"
    const val THOUGHT_NOTIFICATION_ID = 2

    fun init(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(THOUGHT_CHANNEL_ID, "Мысли Рури", NotificationManager.IMPORTANCE_HIGH)
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    internal fun notificationIdFor(messageId: String?): Int =
        messageId?.takeIf { it.isNotBlank() }?.hashCode() ?: System.currentTimeMillis().hashCode()

    fun thought(ctx: Context, text: String, messageId: String? = null) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            init(ctx)
            val intent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (!messageId.isNullOrBlank()) putExtra("message_id", messageId)
            }
            val pi = PendingIntent.getActivity(
                ctx, messageId?.hashCode() ?: 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(ctx, THOUGHT_CHANNEL_ID)
                .setContentTitle("Рури думает")
                .setContentText(text.take(110))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(notificationIdFor(messageId), notification)
        } catch (e: SecurityException) {
            android.util.Log.w("Ruri", "Notification permission denied", e)
        } catch (e: Exception) {
            android.util.Log.e("Ruri", "Notification failed", e)
        }
    }
}