package com.ruri

import org.json.JSONObject
import java.io.File

/** Compatibility flag plus the durable per-message outbox. */
object OfflineQueue {
    private const val STATE_PATH = "memory/L4_state.json"

    private fun readState(root: File): JSONObject =
        JSONObject(FileManager.read(root, STATE_PATH))

    fun markPending(root: File, messageId: String? = null) {
        if (!messageId.isNullOrBlank()) CommanderReplyQueue.enqueue(root, messageId)
        FileManager.updateL4State(root) { state ->
            state.put("pending_reply", true)
                .put("pending_reply_marked_at", System.currentTimeMillis())
        }
    }

    fun hasPending(root: File): Boolean = try {
        readState(root).optBoolean("pending_reply", false) || CommanderReplyQueue.hasPending(root)
    } catch (_: Exception) {
        CommanderReplyQueue.hasPending(root)
    }

    fun clear(root: File) {
        val stillPending = CommanderReplyQueue.hasPending(root)
        FileManager.updateL4State(root) { state ->
            state.put("pending_reply", stillPending)
            if (!stillPending) state.put("pending_reply_cleared_at", System.currentTimeMillis())
        }
    }
}
