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
            val outcome = runCatching { ChatEngine.respond(root) }

            assertTrue(outcome.isFailure)
            // The failure must name the missing key. This used to match the word "Routeway",
            // which was provider-specific wording that has since been made neutral.
            val message = outcome.exceptionOrNull()?.message.orEmpty()
            assertTrue("must explain the missing key: $message",
                message.contains("ключ", ignoreCase = true))
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
