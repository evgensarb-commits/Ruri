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
                    if (OfflineQueue.hasPending(context.filesDir)) Thinker.wake(context, "online_after_offline")
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
            ShelfWatcher.scan(appContext)
            detectResearchTask(appContext)
            try { LocalBackupManager.createIfDue(root) }
            catch (backupError: Exception) { Log.e("Ruri", "Automatic local backup failed; life cycle will continue", backupError) }
            val pending = EventQueue.snapshot(root)
            runResearchTasks(root, pending)
            val reason = if (pending.isEmpty()) {
                "проверка_состояния"
            } else {
                "обработка_событий:" + pending
                    .map { it.optString("reason") }
                    .distinct()
                    .joinToString(",")
                    .take(300)
            }

            // Cheap reachability probes are rate-limited locally and never consume model quota.
            if (NetState.online) {
                val routeConfig = try { JSONObject(SecretStore.readRuntimeConfig(root)) } catch (_: Exception) { JSONObject() }
                if (reason.contains("net_change")) {
                    Probe.refreshReachability(
                        root, routeConfig.optJSONArray("models") ?: org.json.JSONArray(),
                        topOnly = false, force = true
                    )
                }
                Probe.heartbeat(root, routeConfig)
                    }

            // Do useful local work first; it does not require an API key or network.
            LifeStateEngine.process(appContext, reason)
            ShelfWatcher.ingestPending(appContext, pending)
            FactExtractor.extractIfDue(appContext)
            MemoryConsolidator.consolidateIfDue(appContext)

            // A queued Commander turn is answered once connectivity returns. The transient
            // offline notice is never appended to L0; ChatEngine records only a real reply.
            if (NetState.online && (OfflineQueue.hasPending(root) || CommanderReplyQueue.hasPending(root))) {
                CommanderReplyQueue.recoverLatestUnanswered(root)
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
                        continue
                    }
                    try {
                        val reply = ChatEngine.respond(root, commanderId)
                        val replyId = ChatEngine.lastSavedReplyId()
                        if (reply != ChatEngine.OFFLINE_REPLY) Notifier.thought(appContext, reply, replyId)
                    } catch (drainError: Exception) {
                        val delay = CommanderReplyQueue.nextDelayMs(root) ?: 30_000L
                        LifeScheduler.scheduleCommanderReplyRetry(appContext, delay)
                        Log.w("Ruri", "Commander reply remains queued; retry scheduled", drainError)
                        break
                    }
                }
                runCatching { OfflineQueue.clear(root) }
            }

            try {
                BackupPolicy.checkpointState(root)
                val backupTrigger = if (reason.contains("significant")) "significant" else "maintenance"
                BackupPolicy.maybeRolling(appContext, backupTrigger)
            } catch (backupError: Exception) {
                Log.e("Ruri", "Rolling backup checkpoint failed; life cycle will continue", backupError)
            }

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


    private fun detectResearchTask(context: Context) {
        val root = context.filesDir
        val lines = FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 120, maxBytes = 256 * 1024)
        val latest = lines.asReversed().asSequence().mapNotNull {
            runCatching { JSONObject(it) }.getOrNull()
        }.firstOrNull { it.optString("type") == "message" && it.optString("who") == "commander" } ?: return
        val id = latest.optString("id").ifBlank { latest.optLong("ts").toString() }
        val message = latest.optString("text").trim()
        val pattern = Regex("(?iu)(?:что такое|кто такой|кто такая|что значит|объясни термин|объясни понятие|расскажи о)\\s+([^?!.\\n]{2,100})")
        val term = pattern.find(message)?.groupValues?.get(1)?.trim()?.take(100)
        var claimed = false
        FileManager.updateL4State(root) { state ->
            if (state.optString("last_research_detection_id") != id) {
                state.put("last_research_detection_id", id)
                claimed = true
            }
        }
        if (claimed && !term.isNullOrBlank()) LifeScheduler.enqueueEvent(context, "research_task:" + term)
    }

    private fun runResearchTasks(root: java.io.File, pending: List<JSONObject>) {
        val queries = pending.mapNotNull { item ->
            val reason = item.optString("reason")
            when {
                reason.startsWith("research_task:") -> reason.removePrefix("research_task:").trim()
                reason.startsWith("capability_gap:") -> reason.removePrefix("capability_gap:").trim()
                reason.startsWith("unknown_term:") -> reason.removePrefix("unknown_term:").trim()
                else -> null
            }
        }.filter { it.length >= 2 }.distinct().take(3)
        for (query in queries) {
            val response = runCatching { JSONObject(WebSearch.search(root, query)) }
                .getOrElse { JSONObject().put("error", "Поиск недоступен") }
            val results = response.optJSONArray("results") ?: org.json.JSONArray()
            val summary = if (response.has("error")) response.optString("error") else buildString {
                for (i in 0 until minOf(results.length(), 7)) {
                    val item = results.optJSONObject(i) ?: continue
                    append(item.optString("title")).append("\n")
                    append(item.optString("snippet")).append("\n")
                    append(item.optString("url")).append("\n\n")
                }
            }.take(10000)
            val eventId = "research-" + System.currentTimeMillis() + "-" + query.hashCode()
            FileManager.appendEvent(root, JSONObject()
                .put("id", eventId).put("ts", System.currentTimeMillis())
                .put("type", "web_research_result").put("source", "google_search")
                .put("query", query).put("text", summary).put("result_count", results.length())
                .put("privacy", "public_web"))
            if (results.length() > 0) {
                val factsFile = runCatching { JSONObject(FileManager.read(root, "memory/L1_facts.json")) }
                    .getOrDefault(JSONObject().put("facts", org.json.JSONArray()))
                val facts = factsFile.optJSONArray("facts") ?: org.json.JSONArray()
                for (i in 0 until minOf(results.length(), 7)) {
                    val item = results.optJSONObject(i) ?: continue
                    val title = item.optString("title").trim()
                    val snippet = item.optString("snippet").trim()
                    if (title.isBlank() || snippet.length < 20) continue
                    val factText = (title + ": " + snippet).take(400)
                    val exists = (0 until facts.length()).any { facts.optJSONObject(it)?.optString("text") == factText }
                    if (!exists && facts.length() < 500) facts.put(JSONObject()
                        .put("id", eventId + "-" + i).put("text", factText)
                        .put("category", "web_research").put("confidence", 0.65)
                        .put("explicit", false).put("sensitivity", "public")
                        .put("source_url", item.optString("url"))
                        .put("source_event_ids", org.json.JSONArray().put(eventId))
                        .put("origin", "google_search").put("created_at", System.currentTimeMillis()))
                }
                factsFile.put("facts", facts).put("updated_at", System.currentTimeMillis())
                FileManager.write(root, "memory/L1_facts.json", factsFile.toString(2))
            }
        }
    }

    private fun shouldRunRemoteCognition(context: Context, reason: String): Boolean {
        val root = context.filesDir
        val now = System.currentTimeMillis()
        val notificationEvent = reason.contains("phone_notification")
        val explicitSelfTask = reason.contains("research_task") || reason.contains("capability_gap")
        val driveCheckEvent = reason.contains("drive_check")

        if (!driveCheckEvent) {
            val drift = EmotionEngine.drift(root)
            val interval = drift.optDouble("drive_check_interval_min", 45.0)
            var claimed = false
            FileManager.updateL4State(root) { latest ->
                if (LifeScheduler.driveCheckDue(now, latest.optLong("last_drive_check", 0L), interval)) {
                    latest.put("last_drive_check", now)
                    claimed = true
                }
            }
            if (claimed && EmotionEngine.pressure(root) >= drift.optDouble("initiative_threshold", 40.0)) {
                LifeScheduler.enqueueEvent(context, "drive_check")
            }
        }

        if (!driveCheckEvent && !notificationEvent && !explicitSelfTask) return false

        val config = try { JSONObject(SecretStore.readRuntimeConfig(root)) } catch (_: Exception) { return false }
        if (!NetState.online) return false
        val models = config.optJSONArray("models") ?: return false
        val task = if (driveCheckEvent) Task.WARMTH else if (explicitSelfTask) Task.TOOLS else Task.REASON
        return FreeModelRouter.orderedIndices(
            root, models, config.optInt("current", 0), task
        ).isNotEmpty()
    }
}
