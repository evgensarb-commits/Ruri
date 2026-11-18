package com.ruri

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
    private val interval = 30L * 60L * 1000L
    private val cycle = object : Runnable {
        override fun run() {
            Thinker.wake(this@RuriService, "проверка_состояния")
            handler.postDelayed(this, interval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("РУ-R1")
            .setContentText("Фоновая жизнь активна")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        handler.removeCallbacks(cycle)
        Thinker.wake(this, "запуск_службы")
        handler.postDelayed(cycle, interval)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(cycle)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Жизнь Рури", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}