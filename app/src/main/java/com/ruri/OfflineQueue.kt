package com.ruri

import org.json.JSONObject
import java.io.File

/** Durable marker for a Commander message that could not be answered while offline. */
object OfflineQueue {
    private const val STATE_PATH = "memory/L4_state.json"

    private fun readState(root: File): JSONObject =
        JSONObject(FileManager.read(root, STATE_PATH))

    fun markPending(root: File) {
        val state = readState(root)
        state.put("pending_reply", true)
        state.put("pending_reply_marked_at", System.currentTimeMillis())
        FileManager.write(root, STATE_PATH, state.toString(2))
    }

    fun hasPending(root: File): Boolean = try {
        readState(root).optBoolean("pending_reply", false)
    } catch (_: Exception) {
        false
    }

    fun clear(root: File) {
        val state = readState(root)
        state.put("pending_reply", false)
        state.put("pending_reply_cleared_at", System.currentTimeMillis())
        FileManager.write(root, STATE_PATH, state.toString(2))
    }
}
