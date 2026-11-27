package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.min
import kotlin.math.pow

/**
 * Durable per-message outbox. L0 remains the source of message text; this file stores IDs
 * and retry metadata only, so provider failures never discard a Commander turn.
 */
object CommanderReplyQueue {
    private const val PATH = "memory/commander_reply_queue.json"
    private const val MAX_PENDING = 5000
    private const val MAX_COMPLETED = 500
    private const val BASE_BACKOFF_MS = 30_000L
    private const val MAX_BACKOFF_MS = 6L * 60L * 60L * 1000L
    private val lock = Any()

    fun enqueue(root: File, messageId: String, now: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            if (messageId.isBlank() || isCompletedLocked(root, messageId)) return@synchronized false
            val state = readState(root)
            val pending = state.optJSONArray("pending") ?: JSONArray()
            if ((0 until pending.length()).any { pending.optJSONObject(it)?.optString("message_id") == messageId }) {
                return@synchronized false
            }
            if (pending.length() >= MAX_PENDING) return@synchronized false
            pending.put(JSONObject()
                .put("message_id", messageId)
                .put("created_at", now)
                .put("attempts", 0)
                .put("next_attempt_at", now)
                .put("last_error_kind", ""))
            state.put("schema_version", 1).put("pending", pending).put("updated_at", now)
            writeState(root, state)
            true
        }

    fun hasPending(root: File): Boolean = synchronized(lock) {
        (readState(root).optJSONArray("pending")?.length() ?: 0) > 0
    }

    /**
     * Is there anything the app will retry on its own?
     *
     * A message parked for a manual retry (terminal provider failure, or 60 attempts spent) stays
     * in `pending` until the Commander presses the button. Treating that as "busy" suspended her
     * diary, her initiative and the next message as well — she went silent indefinitely.
     */
    fun hasAutoRetryPending(root: File): Boolean = synchronized(lock) {
        val pending = readState(root).optJSONArray("pending") ?: return@synchronized false
        (0 until pending.length()).any { index ->
            val item = pending.optJSONObject(index) ?: return@any false
            !item.optBoolean("manual_retry", false)
        }
    }

    fun pendingCount(root: File): Int = synchronized(lock) {
        readState(root).optJSONArray("pending")?.length() ?: 0
    }

    fun firstPending(root: File): JSONObject? = synchronized(lock) {
        val pending = readState(root).optJSONArray("pending") ?: return@synchronized null
        pending.optJSONObject(0)?.let { JSONObject(it.toString()) }
    }

    fun nextDue(root: File, now: Long = System.currentTimeMillis()): JSONObject? =
        firstPending(root)?.takeIf {
            !it.optBoolean("manual_retry", false) && it.optLong("next_attempt_at", 0L) <= now
        }

    fun nextDelayMs(root: File, now: Long = System.currentTimeMillis()): Long? = synchronized(lock) {
        val first = readState(root).optJSONArray("pending")?.optJSONObject(0) ?: return@synchronized null
        // A terminal failure waits for the Commander's button; nothing is scheduled.
        if (first.optBoolean("manual_retry", false)) return@synchronized null
        (first.optLong("next_attempt_at", now) - now).coerceAtLeast(0L)
    }

    /** True when the head of the queue failed terminally and waits for the retry button. */
    fun needsManualRetry(root: File): Boolean = synchronized(lock) {
        val first = readState(root).optJSONArray("pending")?.optJSONObject(0) ?: return@synchronized false
        first.optBoolean("manual_retry", false)
    }

    /** The Commander pressed «ПОВТОРИТЬ»: make the head message due immediately. */
    fun manualRetry(root: File, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val state = readState(root)
        val pending = state.optJSONArray("pending") ?: return@synchronized
        val first = pending.optJSONObject(0) ?: return@synchronized
        first.put("manual_retry", false)
            .put("next_attempt_at", now)
            .put("attempts", 0)
        state.put("pending", pending).put("updated_at", now)
        writeState(root, state)
        Unit
    }

    /** Snapshot for the chat UI. */
    fun status(root: File, now: Long = System.currentTimeMillis()): JSONObject = synchronized(lock) {
        val state = readState(root)
        val pending = state.optJSONArray("pending") ?: JSONArray()
        val first = pending.optJSONObject(0)
        if (first == null) {
            return@synchronized JSONObject()
                .put("pending", 0)
                .put("manual_retry", false)
                .put("next_attempt_at", 0L)
                .put("attempts", 0)
                .put("last_error_kind", "")
                .put("last_error_text", "")
        }
        val manual = first.optBoolean("manual_retry", false)
        val next = first.optLong("next_attempt_at", 0L)
        val kind = first.optString("last_error_kind")
        JSONObject()
            .put("pending", pending.length())
            .put("manual_retry", manual)
            .put("next_attempt_at", if (manual) 0L else next)
            .put("attempts", first.optInt("attempts", 0))
            .put("last_error_kind", kind)
            .put("last_error_label", RetryPolicy.label(kind))
            .put("last_error_text", first.optString("last_error_text"))
            .put("explanation", if (kind.isBlank()) "" else RetryPolicy.explain(kind))
    }

    fun recordAttempt(root: File, messageId: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        updatePending(root, messageId) { item ->
            item.put("attempts", item.optInt("attempts", 0) + 1)
                .put("last_attempt_at", now)
        }
    }

    /** Exponential backoff with up to 20% positive jitter; Retry-After wins, capped at 24h. */
    internal fun delayWithJitter(backoffMs: Long, retryAfterMs: Long, random: Double = Math.random()): Long {
        val jitter = (backoffMs * 0.2 * random).toLong()
        return maxOf(backoffMs + jitter, retryAfterMs.coerceAtLeast(0L)).coerceAtMost(24L * 60L * 60L * 1000L)
    }

    /**
     * Records a failed attempt and schedules the next one.
     *
     * Transient failures are retried every ten seconds ([RetryPolicy.AUTO_RETRY_DELAY_MS]).
     * Terminal failures (no VPN, exhausted token quota, rejected key, no credits) set
     * `manual_retry`, so nothing is scheduled: only the «ПОВТОРИТЬ» button resumes the turn.
     */
    fun recordFailure(
        root: File,
        messageId: String,
        kind: String,
        retryAfterMs: Long = 0L,
        now: Long = System.currentTimeMillis()
    ): Long = synchronized(lock) {
        var delay = 0L
        updatePending(root, messageId) { item ->
            val attempts = item.optInt("attempts", 1).coerceAtLeast(1)
            val auto = RetryPolicy.autoDelayMs(kind, attempts)
            delay = when {
                auto <= 0L -> 0L
                retryAfterMs > 0L -> maxOf(auto, retryAfterMs.coerceAtMost(MAX_BACKOFF_MS))
                else -> auto
            }
            val manual = delay == 0L
            item.put("last_error_kind", kind.take(40))
                .put("last_failed_at", now)
                .put("manual_retry", manual)
                .put("next_attempt_at", if (manual) now else now + delay)
            if (manual) item.put("manual_retry_at", now)
        }
        delay
    }

    fun markAnswered(root: File, messageId: String, replyId: String = "", now: Long = System.currentTimeMillis()) =
        synchronized(lock) {
            if (messageId.isBlank()) return@synchronized
            val state = readState(root)
            val pending = state.optJSONArray("pending") ?: JSONArray()
            val remaining = JSONArray()
            for (i in 0 until pending.length()) {
                val item = pending.optJSONObject(i) ?: continue
                if (item.optString("message_id") != messageId) remaining.put(item)
            }
            val completed = state.optJSONArray("completed") ?: JSONArray()
            if (!isCompletedLocked(root, messageId)) {
                completed.put(JSONObject().put("message_id", messageId)
                    .put("reply_id", replyId).put("answered_at", now))
            }
            while (completed.length() > MAX_COMPLETED) completed.remove(0)
            state.put("pending", remaining).put("completed", completed).put("updated_at", now)
            writeState(root, state)
        }

    fun markDeleted(root: File, messageId: String) = synchronized(lock) {
        val state = readState(root)
        val pending = state.optJSONArray("pending") ?: JSONArray()
        val remaining = JSONArray()
        for (i in 0 until pending.length()) {
            val item = pending.optJSONObject(i) ?: continue
            if (item.optString("message_id") != messageId) remaining.put(item)
        }
        state.put("pending", remaining).put("updated_at", System.currentTimeMillis())
        writeState(root, state)
    }

    fun isAnswered(root: File, messageId: String): Boolean = synchronized(lock) {
        isCompletedLocked(root, messageId)
    }

    /** Recovery for installations that only had the legacy pending_reply boolean. */
    fun recoverUnanswered(root: File): String? = synchronized(lock) {
        val state = readState(root)
        val pending = state.optJSONArray("pending") ?: JSONArray()
        val queuedIds = (0 until pending.length())
            .mapNotNull { pending.optJSONObject(it)?.optString("message_id") }.toMutableSet()
        var freeSlots = (MAX_PENDING - pending.length()).coerceAtLeast(0)
        if (freeSlots == 0) return@synchronized null
        val completed = state.optJSONArray("completed") ?: JSONArray()
        val completedIds = (0 until completed.length())
            .mapNotNull { completed.optJSONObject(it)?.optString("message_id") }.toSet()
        val receipts = runCatching { JSONObject(Receipts.export(root)) }.getOrElse { JSONObject() }
        val lastSeen = runCatching {
            JSONObject(FileManager.read(root, "memory/L4_state.json")).optLong("last_seen_commander", 0L)
        }.getOrDefault(0L)
        val lines = FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 5000, maxBytes = 4 * 1024 * 1024)
        val events = lines.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        val repliedIds = events.asSequence()
            .filter { it.optString("type") == "message" && it.optString("who") == "ruri" }
            .map { it.optString("reply_to") }.filter { it.isNotBlank() }.toSet()
        val deletedIds = Tombstone.ids(root)
        var firstRecovered: String? = null
        for (event in events) {
            if (freeSlots <= 0) break
            val id = event.optString("id")
            val timestamp = event.optLong("ts", event.optLong("timestamp", 0L))
            if (event.optString("type") != "message" || event.optString("who") != "commander" ||
                id.isBlank() || timestamp < lastSeen || timestamp <= 0L ||
                id in queuedIds || id in completedIds || id in repliedIds || id in deletedIds ||
                receipts.optJSONObject(id)?.optBoolean("replied_by_ruri", false) == true) continue
            if (enqueue(root, id)) {
                queuedIds.add(id)
                freeSlots--
                if (firstRecovered == null) firstRecovered = id
            }
        }
        firstRecovered
    }

    private fun isCompletedLocked(root: File, messageId: String): Boolean {
        val completed = readState(root).optJSONArray("completed") ?: return false
        return (0 until completed.length()).any { completed.optJSONObject(it)?.optString("message_id") == messageId }
    }

    private fun updatePending(root: File, messageId: String, mutate: (JSONObject) -> Unit) {
        val state = readState(root)
        val pending = state.optJSONArray("pending") ?: JSONArray()
        for (i in 0 until pending.length()) {
            val item = pending.optJSONObject(i) ?: continue
            if (item.optString("message_id") == messageId) {
                mutate(item)
                state.put("pending", pending).put("updated_at", System.currentTimeMillis())
                writeState(root, state)
                return
            }
        }
    }

    private fun readState(root: File): JSONObject {
        val raw = FileManager.read(root, PATH)
        if (raw.isBlank()) return JSONObject().put("schema_version", 1).put("pending", JSONArray()).put("completed", JSONArray())
        return try {
            JSONObject(raw).also {
                if (it.optJSONArray("pending") == null) throw IllegalArgumentException("Missing pending array")
                if (it.optJSONArray("completed") == null) it.put("completed", JSONArray())
            }
        } catch (e: Exception) {
            runCatching {
                val backup = File(root, "backup/commander_reply_queue_corrupt_${System.currentTimeMillis()}.json")
                backup.parentFile?.mkdirs()
                if (!backup.exists()) backup.writeText(raw, Charsets.UTF_8)
            }
            JSONObject().put("schema_version", 1).put("pending", JSONArray()).put("completed", JSONArray())
                .put("recovery_warning", "queue_corrupt")
        }
    }

    private fun writeState(root: File, state: JSONObject) {
        FileManager.write(root, PATH, state.toString(2))
    }
}
