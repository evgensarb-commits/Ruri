package com.ruri

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
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
    private const val REPLY_RETRY_NAME = "ru-r1-commander-reply-retry"
    @Volatile private var networkCallbackRegistered = false

    internal fun driveCheckDue(now: Long, lastDriveCheck: Long, intervalMinutes: Double): Boolean =
        intervalMinutes > 0.0 && (lastDriveCheck <= 0L || now - lastDriveCheck >= intervalMinutes * 60000.0)

    fun ensurePeriodic(context: Context) {
        registerConnectivityWake(context.applicationContext)
        val request = PeriodicWorkRequestBuilder<RuriLifeWorker>(15, TimeUnit.MINUTES)
            .addTag(PERIODIC_NAME)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun registerConnectivityWake(context: Context) {
        if (networkCallbackRegistered) return
        synchronized(this) {
            if (networkCallbackRegistered) return
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.registerNetworkCallback(NetworkRequest.Builder().build(), object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (OfflineQueue.hasPending(context.filesDir) || CommanderReplyQueue.hasPending(context.filesDir)) {
                        Thinker.wake(context, "online_after_offline")
                    }
                }
            })
            networkCallbackRegistered = true
        }
    }

    fun scheduleCommanderReplyRetry(context: Context, delayMs: Long) {
        val delay = delayMs.coerceAtLeast(1_000L)
        val request = OneTimeWorkRequestBuilder<RuriLifeWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(REPLY_RETRY_NAME)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(REPLY_RETRY_NAME, ExistingWorkPolicy.REPLACE, request)
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
            NetState.refresh(appContext)
            // Every wake is an opportunity for her to feel something, but the decision to
            // write is taken by the emotional gates inside Thinker — never by this timer.
            // Without this marker a routine wake would never reach cognition at all.
            EventQueue.enqueue(root, "drive_check")
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

            // Update local state before replying; this path does not require a model request.
            LifeStateEngine.process(appContext, reason)

            // Rehydrate the durable per-message outbox from L0 after process death. New
            // Commander events share one EventQueue reason so a long offline burst stays compact.
            val commanderWake = pending.any { item ->
                val eventReason = item.optString("reason")
                eventReason == "commander_messages_pending" || eventReason.startsWith("commander_message:")
            }
            pending.forEach { item ->
                val eventReason = item.optString("reason")
                if (eventReason.startsWith("commander_message:")) {
                    val messageId = eventReason.removePrefix("commander_message:").trim()
                    if (messageId.isNotBlank()) runCatching { CommanderReplyQueue.enqueue(root, messageId) }
                        .onFailure { Log.w("Ruri", "Could not rehydrate Commander reply queue item", it) }
                }
            }

            // Every failed turn remains queued; a provider error never acknowledges the message.
            if (commanderWake || OfflineQueue.hasPending(root) || CommanderReplyQueue.hasPending(root)) {
                CommanderReplyQueue.recoverUnanswered(root)
                while (true) {
                    val queued = CommanderReplyQueue.nextDue(root)
                    if (queued == null) {
                        val delay = CommanderReplyQueue.nextDelayMs(root)
                        if (delay != null && delay > 0L) LifeScheduler.scheduleCommanderReplyRetry(appContext, delay)
                        break
                    }
                    val commanderId = queued.optString("message_id")
                    if (commanderId.isBlank()) break
                    if (Tombstone.isDeleted(root, commanderId)) {
                        CommanderReplyQueue.markDeleted(root, commanderId)
                        CommanderReplyQueue.recoverUnanswered(root)
                        continue
                    }
                    try {
                        val reply = ChatEngine.respond(root, commanderId)
                        if (!CommanderReplyQueue.isAnswered(root, commanderId)) {
                            throw IllegalStateException("Модель вернула управление без сохранённого ответа; сообщение оставлено в очереди")
                        }
                        val replyId = ChatEngine.lastSavedReplyId()
                        if (reply != ChatEngine.OFFLINE_REPLY) Notifier.thought(appContext, reply, replyId)
                        CommanderReplyQueue.recoverUnanswered(root)
                    } catch (drainError: Exception) {
                        val delay = CommanderReplyQueue.nextDelayMs(root) ?: 30_000L
                        LifeScheduler.scheduleCommanderReplyRetry(appContext, delay)
                        Log.w("Ruri", "Commander reply remains queued; retry scheduled", drainError)
                        break
                    }
                }
                runCatching { OfflineQueue.clear(root) }
            }

            // Non-model maintenance follows the Commander queue so it cannot delay a reply.
            try { LocalBackupManager.createIfDue(root) }
            catch (backupError: Exception) { Log.e("Ruri", "Automatic local backup failed; life cycle will continue", backupError) }

            // Commander replies have priority over optional memory/model maintenance.
            // Do not let extraction or consolidation consume provider capacity while a reply waits.
            if (!OfflineQueue.hasPending(root) && !CommanderReplyQueue.hasPending(root)) {
                FactExtractor.extractIfDue(appContext)
                if (!OfflineQueue.hasPending(root) && !CommanderReplyQueue.hasPending(root)) {
                    MemoryConsolidator.consolidateIfDue(appContext)
                }
                // Her own bounded reading pass. It runs after the mechanisms above so it can only
                // use capacity they left, and it stops on its own budget instead of looping.
                if (!OfflineQueue.hasPending(root) && !CommanderReplyQueue.hasPending(root)) {
                    runCatching { ReflectionRunner.runIfDue(appContext) }
                        .onFailure { Log.w("Ruri", "Reflection pass failed; cycle continues", it) }
                }
            }

            try {
                BackupPolicy.checkpointState(root)
                val backupTrigger = if (reason.contains("significant")) "significant" else "maintenance"
                BackupPolicy.maybeRolling(appContext, backupTrigger)
            } catch (backupError: Exception) {
                Log.e("Ruri", "Rolling backup checkpoint failed; life cycle will continue", backupError)
            }

            // A pending Commander reply takes priority over optional autonomous cognition.
            // This is task ordering, not an online/quota/battery preflight.
            val commanderReplyPending = OfflineQueue.hasPending(root) || CommanderReplyQueue.hasPending(root)
            if (!commanderReplyPending && shouldRunRemoteCognition(appContext, reason)) {
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


    /**
     * Decides only whether a cognition cycle is worth starting at all.
     *
     * No interval and no pressure threshold live here: every worker wake enqueues
     * `drive_check`, and [EmotionEngine.initiativeGate] / [EmotionEngine.thoughtGate]
     * inside [Thinker] decide alone whether she writes, thinks or stays silent.
     */
    private fun shouldRunRemoteCognition(context: Context, reason: String): Boolean {
        val notificationEvent = reason.contains("phone_notification") && !reason.contains("removed")
        val explicitSelfTask = reason.contains("research_task") || reason.contains("capability_gap")
        val driveCheckEvent = reason.contains("drive_check")

        if (!driveCheckEvent && !notificationEvent && !explicitSelfTask) return false
        if (!Route.hasKey(context.filesDir)) return false
        // Her own initiative must not consume the Commander's daily allowance. This bounds
        // autonomous cognition only; a Commander message always reaches the provider.
        return RouteMeta.autonomyBudgetAvailable(context.filesDir)
    }
}
