package com.ruri

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class RuriService : Service() {

    companion object {
        const val CHANNEL_ID = "ruri_life"
        const val NOTIFICATION_ID = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tickInterval = 30 * 60 * 1000L // 30 минут

    private val tickRunnable = object : Runnable {
        override fun run() {
            Thinker.wake(this@RuriService, "таймер_покоя_30_минут")
            handler.postDelayed(this, tickInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("РУ-R1 живёт")
            .setContentText("Непрерывное существование")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        // Первый тик через 5 минут после старта, чтобы не грузить систему сразу
        handler.postDelayed(tickRunnable, 5 * 60 * 1000L)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Жизнь Рури",
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
