package com.ruri

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * User-visible companion presence.
 *
 * Long-term cadence is delegated to WorkManager; this service is not used as
 * an endless timer because Android may stop background processes.
 */
class RuriService : Service() {
    companion object {
        const val CHANNEL_ID = "ruri_life"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        DeviceEventMonitor.register(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RU-R1 · Рури")
            .setContentText("Состояние и планы сохраняются; фоновые задачи запланированы")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        LifeScheduler.ensurePeriodic(this)
        Thinker.wake(this, "запуск_службы")
        return START_STICKY
    }

    override fun onDestroy() {
        DeviceEventMonitor.unregister(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Жизнь Рури",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
