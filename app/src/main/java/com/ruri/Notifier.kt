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
            val channel = NotificationChannel(
                THOUGHT_CHANNEL_ID,
                "Мысли Рури",
                NotificationManager.IMPORTANCE_HIGH
            )
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun thought(ctx: Context, text: String) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) return

            init(ctx)
            val intent = Intent(ctx, MainActivity::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getActivity(ctx, 0, intent, flags)
            val notification = NotificationCompat.Builder(ctx, THOUGHT_CHANNEL_ID)
                .setContentTitle("Рури думает")
                .setContentText(text.take(100))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()

            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(THOUGHT_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            android.util.Log.w("Ruri", "Notification permission not granted", e)
        } catch (e: Exception) {
            android.util.Log.e("Ruri", "Unable to post thought notification", e)
        }
    }
}