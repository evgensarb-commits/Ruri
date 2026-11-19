package com.ruri

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineQueueTest {
    @Test
    fun offlineChatReturnsTransientAndDoesNotAppendItToL0() {
        val root = Files.createTempDirectory("ruri-offline-chat").toFile()
        try {
            FileManager.write(root, "memory/L4_state.json", JSONObject().put("mood", "собранная").toString())
            val response = ChatEngine.respond(root)

            assertTrue(response.contains("Нет доступного маршрута"))
            assertTrue(OfflineQueue.hasPending(root))
            assertFalse(FileManager.exists(root, "memory/L0_raw.jsonl"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pendingReplySurvivesUntilARealReplyIsStored() {
        val root = Files.createTempDirectory("ruri-offline-queue").toFile()
        try {
            FileManager.write(root, "memory/L4_state.json", JSONObject().put("mood", "собранная").toString())
            assertFalse(OfflineQueue.hasPending(root))
            OfflineQueue.markPending(root)
            assertTrue(OfflineQueue.hasPending(root))
            val state = JSONObject(FileManager.read(root, "memory/L4_state.json"))
            assertTrue(state.optLong("pending_reply_marked_at") > 0L)
            OfflineQueue.clear(root)
            assertFalse(OfflineQueue.hasPending(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
