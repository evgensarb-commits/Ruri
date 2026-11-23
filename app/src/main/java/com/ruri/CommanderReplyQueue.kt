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
            require(pending.length() < MAX_PENDING) { "Очередь ответов переполнена; сообщение осталось в L0" }
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

    fun pendingCount(root: File): Int = synchronized(lock) {
        readState(root).optJSONArray("pending")?.length() ?: 0
    }

    fun nextDue(root: File, now: Long = System.currentTimeMillis()): JSONObject? = synchronized(lock) {
        val pending = readState(root).optJSONArray("pending") ?: return@synchronized null
        for (i in 0 until pending.length()) {
            val item = pending.optJSONObject(i) ?: continue
            if (item.optLong("next_attempt_at", 0L) <= now) return@synchronized JSONObject(item.toString())
            // Preserve FIFO: a later message cannot jump ahead of an earlier unanswered one.
            return@synchronized null
        }
        null
    }

    fun nextDelayMs(root: File, now: Long = System.currentTimeMillis()): Long? = synchronized(lock) {
        val first = readState(root).optJSONArray("pending")?.optJSONObject(0) ?: return@synchronized null
        (first.optLong("next_attempt_at", now) - now).coerceAtLeast(0L)
    }

    fun recordAttempt(root: File, messageId: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        updatePending(root, messageId) { item ->
            item.put("attempts", item.optInt("attempts", 0) + 1)
                .put("last_attempt_at", now)
        }
    }

    /** Returns the durable delay until the next retry. No failure kind removes the message. */
    fun recordFailure(
        root: File,
        messageId: String,
        kind: String,
        retryAfterMs: Long = 0L,
        now: Long = System.currentTimeMillis()
    ): Long = synchronized(lock) {
        var delay = BASE_BACKOFF_MS
        updatePending(root, messageId) { item ->
            val attempts = item.optInt("attempts", 1).coerceAtLeast(1)
            val exponent = (attempts - 1).coerceIn(0, 10)
            val backoff = min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (2.0.pow(exponent.toDouble())).toLong())
            delay = maxOf(backoff, retryAfterMs.coerceAtLeast(0L)).coerceAtMost(24L * 60L * 60L * 1000L)
            item.put("next_attempt_at", now + delay)
                .put("last_error_kind", kind.take(40))
                .put("last_failed_at", now)
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
    fun recoverLatestUnanswered(root: File): String? = synchronized(lock) {
        val pending = readState(root).optJSONArray("pending") ?: JSONArray()
        if (pending.length() > 0) return@synchronized null
        val receipts = runCatching { JSONObject(Receipts.export(root)) }.getOrElse { JSONObject() }
        val lines = FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 1000)
        val latest = lines.asReversed().asSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .firstOrNull { event ->
                event.optString("type") == "message" && event.optString("who") == "commander" &&
                    event.optString("id").isNotBlank() &&
                    !Tombstone.isDeleted(root, event.optString("id")) &&
                    !receipts.optJSONObject(event.optString("id"))?.optBoolean("replied_by_ruri", false).let { it == true }
            } ?: return@synchronized null
        val id = latest.optString("id")
        enqueue(root, id)
        id
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
