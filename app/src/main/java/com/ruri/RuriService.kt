package com.ruri

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.app.NotificationCompat

/**
 * User-visible companion presence.
 *
 * Long-term cadence is delegated to WorkManager; this service is not used as
 * an endless timer because Android may stop background processes.
 */
class RuriService : Service() {
    private val networkListener: () -> Unit = {
        LifeScheduler.enqueueEvent(applicationContext, "net_change")
        if (NetState.online && OfflineQueue.hasPending(filesDir)) {
            LifeScheduler.enqueueEvent(applicationContext, "offline_drain")
        }
    }

    companion object {
        const val CHANNEL_ID = "ruri_life"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        FileManager.init(filesDir)
        ShelfWatcher.start(this)
        createChannel()
        DeviceEventMonitor.register(this)
        NetState.register(this)
        NetState.addListener(networkListener)
        Thread({
            runCatching {
                NetState.refresh(applicationContext)
                val config = JSONObject(SecretStore.readRuntimeConfig(filesDir))
                Probe.refreshReachability(filesDir, config.optJSONArray("models") ?: JSONArray(), topOnly = false, force = true)
            }.onFailure { android.util.Log.w("Ruri", "Startup route refresh failed", it) }
        }, "ruri-service-route-refresh").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RU-R1 · Рури")
            .setContentText("Фоновая служба Рури активна")
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        // stopWithTask=false keeps this foreground service alive when the launcher
        // removes only the recent-apps task. Refresh durable scheduling; do not stop/restart the service.
        try {
            LifeScheduler.ensurePeriodic(this)
            Thinker.wake(this, "окно_приложения_смахнуто")
        } catch (e: Exception) {
            android.util.Log.w("Ruri", "Could not refresh life schedule after task removal", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        NetState.removeListener(networkListener)
        NetState.unregister()
        DeviceEventMonitor.unregister(this)
        ShelfWatcher.stop()
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
