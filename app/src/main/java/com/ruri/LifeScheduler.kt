package com.ruri

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import java.util.concurrent.TimeUnit

/**
 * Durable scheduling for RU-R1.
 *
 * WorkManager persists requests across process death and device restarts.
 * Android still controls the exact execution window; 30 minutes is a target
 * cadence, not a promise of real-time execution.
 */
object LifeScheduler {
    private const val PERIODIC_NAME = "ru-r1-periodic-life"
    private const val EVENT_NAME = "ru-r1-event-cognition"

    fun ensurePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<RuriLifeWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(PERIODIC_NAME)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun enqueueEvent(context: Context, reason: String) {
        EventQueue.enqueue(context.filesDir, reason)
        kickEventWorker(context)
    }

    fun kickEventWorker(context: Context) {
        val request = OneTimeWorkRequestBuilder<RuriLifeWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(EVENT_NAME)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(EVENT_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

class RuriLifeWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker(context, parameters) {

    override fun doWork(): Result {
        val root = applicationContext.filesDir
        return try {
            FileManager.init(root)
            val pending = EventQueue.snapshot(root)
            val reason = if (pending.isEmpty()) {
                "проверка_состояния"
            } else {
                "обработка_событий:" + pending
                    .map { it.optString("reason") }
                    .distinct()
                    .joinToString(",")
                    .take(300)
            }

            if (!Thinker.runScheduledCycle(applicationContext, reason)) {
                return Result.retry()
            }

            EventQueue.acknowledge(root, pending)
            if (EventQueue.hasPending(root)) {
                LifeScheduler.kickEventWorker(applicationContext)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("Ruri", "Scheduled life cycle failed", e)
            Result.retry()
        }
    }
}
