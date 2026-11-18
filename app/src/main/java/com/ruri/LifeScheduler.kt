package com.ruri

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Durable scheduling for RU-R1.
 * The 30-minute cadence is a maintenance opportunity, never a real-time promise.
 */
object LifeScheduler {
    private const val PERIODIC_NAME = "ru-r1-periodic-life"
    private const val EVENT_NAME = "ru-r1-event-cognition"

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<RuriLifeWorker>(30, TimeUnit.MINUTES)
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
        val appContext = applicationContext
        val root = appContext.filesDir
        return try {
            FileManager.init(root)
            try { LocalBackupManager.createIfDue(root) }
            catch (backupError: Exception) { Log.e("Ruri", "Automatic local backup failed; life cycle will continue", backupError) }
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

            // Do useful local work first; it does not require an API key or network.
            LifeStateEngine.process(appContext, reason)
            FactExtractor.extractIfDue(appContext)
            MemoryConsolidator.consolidateIfDue(appContext)

            // Avoid spending tokens on every 30-minute maintenance wake-up.
            if (shouldRunRemoteCognition(appContext, reason)) {
                if (!Thinker.runScheduledCycle(appContext, reason)) return Result.retry()
            }

            EventQueue.acknowledge(root, pending)
            val l0Backlog = try {
                JSONObject(FileManager.read(root, "memory/L4_state.json")).optBoolean("l0_backlog", false)
            } catch (_: Exception) { false }
            if (EventQueue.hasPending(root) || l0Backlog) LifeScheduler.kickEventWorker(appContext)
            Result.success()
        } catch (e: Exception) {
            Log.e("Ruri", "Scheduled life cycle failed", e)
            Result.retry()
        }
    }

    private fun shouldRunRemoteCognition(context: Context, reason: String): Boolean {
        val root = context.filesDir
        val state = try { JSONObject(FileManager.read(root, "memory/L4_state.json")) }
            catch (_: Exception) { JSONObject() }
        val now = System.currentTimeMillis()
        val lastSeen = state.optLong("last_seen_commander", 0L)
        val quietHours = if (lastSeen <= 0L) 0.0 else (now - lastSeen).coerceAtLeast(0L) / 3_600_000.0
        val lastInitiative = state.optLong("last_initiative", 0L)
        val initiativeDue = lastSeen > 0L &&
            quietHours >= 3.5 &&
            !state.optBoolean("waiting_for_reply", false) &&
            (lastInitiative == 0L || now - lastInitiative >= 3L * 60L * 60L * 1000L)

        val notificationEvent = reason.contains("phone_notification")
        val explicitSelfTask = reason.contains("research_task") ||
            reason.contains("self_reflection") ||
            reason.contains("capability_gap")
        val reflectionDue = now - state.optLong("last_thought", 0L) >= 6L * 60L * 60L * 1000L

        if (!initiativeDue && !notificationEvent && !explicitSelfTask && !reflectionDue) return false
        if (BatteryLevel.get(context) <= 12 && !initiativeDue) return false

        val config = try { JSONObject(SecretStore.readRuntimeConfig(root)) } catch (_: Exception) { return false }
        val models = config.optJSONArray("models") ?: return false
        return (0 until models.length()).any { index ->
            val model = models.optJSONObject(index)
            model != null && model.optString("api_key").isNotBlank() &&
                model.optString("base_url").isNotBlank() && model.optString("model").isNotBlank()
        }
    }
}
