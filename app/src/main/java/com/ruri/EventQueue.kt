package com.ruri

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small durable wake-up queue.
 *
 * Every raw phone/message event remains in L0. This queue only coalesces
 * reasons to schedule cognition, so bursts of notifications do not create
 * unbounded model calls. Items are acknowledged only after a cycle succeeds.
 */
object EventQueue {
    private const val PATH = "memory/pending_events.json"
    private val lock = Any()

    fun enqueue(root: File, reason: String): String = synchronized(lock) {
        val normalizedReason = reason.trim().take(120).ifBlank { "unspecified_event" }
        val state = readState(root)
        val pending = state.optJSONArray("pending") ?: JSONArray()

        // Coalesce identical pending reasons while retaining every source event in L0.
        for (index in 0 until pending.length()) {
            val existing = pending.optJSONObject(index) ?: continue
            if (existing.optString("reason") == normalizedReason) {
                existing.put("occurrences", existing.optInt("occurrences", 1) + 1)
                existing.put("updated_at", System.currentTimeMillis())
                state.put("pending", pending)
                state.put("updated_at", System.currentTimeMillis())
                FileManager.write(root, PATH, state.toString(2))
                return@synchronized existing.optString("id")
            }
        }

        val id = "queue-" + UUID.randomUUID().toString()
        pending.put(
            JSONObject()
                .put("id", id)
                .put("reason", normalizedReason)
                .put("created_at", System.currentTimeMillis())
                .put("updated_at", System.currentTimeMillis())
                .put("occurrences", 1)
        )
        state.put("schema_version", 1)
        state.put("pending", pending)
        state.put("updated_at", System.currentTimeMillis())
        FileManager.write(root, PATH, state.toString(2))
        id
    }

    fun snapshot(root: File): List<JSONObject> = synchronized(lock) {
        val pending = readState(root).optJSONArray("pending") ?: JSONArray()
        (0 until pending.length()).mapNotNull { index ->
            pending.optJSONObject(index)?.let { JSONObject(it.toString()) }
        }
    }

    fun acknowledge(root: File, processed: List<JSONObject>) = synchronized(lock) {
        if (processed.isEmpty()) return@synchronized
        val revisions = processed.associate {
            it.optString("id") to it.optLong("updated_at", it.optLong("created_at", 0L))
        }
        val state = readState(root)
        val pending = state.optJSONArray("pending") ?: JSONArray()
        val remaining = JSONArray()
        for (index in 0 until pending.length()) {
            val item = pending.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val processedRevision = revisions[id]
            val currentRevision = item.optLong("updated_at", item.optLong("created_at", 0L))
            // Keep the queue item if another same-kind event arrived during cognition.
            if (processedRevision == null || processedRevision != currentRevision) remaining.put(item)
        }
        state.put("pending", remaining)
        state.put("updated_at", System.currentTimeMillis())
        FileManager.write(root, PATH, state.toString(2))
    }

    fun hasPending(root: File): Boolean = synchronized(lock) {
        (readState(root).optJSONArray("pending")?.length() ?: 0) > 0
    }

    private fun readState(root: File): JSONObject {
        val raw = FileManager.read(root, PATH)
        if (raw.isBlank()) return JSONObject().put("schema_version", 1).put("pending", JSONArray())
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            // Preserve the corrupt file for diagnosis; rebuild the in-memory queue only.
            JSONObject().put("schema_version", 1).put("pending", JSONArray())
                .put("recovery_warning", "pending_events.json was unreadable")
        }
    }
}
