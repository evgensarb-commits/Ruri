package com.ruri

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CommanderReplyQueueTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-commander-outbox").toFile()

    @Test fun queueIsPerMessageIdAndDeduplicates() {
        val root = root()
        FileManager.init(root)
        assertTrue(CommanderReplyQueue.enqueue(root, "msg-1", 100L))
        assertFalse(CommanderReplyQueue.enqueue(root, "msg-1", 200L))
        assertTrue(CommanderReplyQueue.enqueue(root, "msg-2", 300L))
        assertEquals(2, CommanderReplyQueue.pendingCount(root))
        assertEquals("msg-1", CommanderReplyQueue.nextDue(root, 300L)?.optString("message_id"))
    }

    @Test fun failedMessageStaysQueuedUntilExplicitlyAnswered() {
        val root = root()
        FileManager.init(root)
        CommanderReplyQueue.enqueue(root, "msg-429", 100L)
        CommanderReplyQueue.recordAttempt(root, "msg-429", 200L)
        val delay = CommanderReplyQueue.recordFailure(root, "msg-429", "rate_limit", 120_000L, 300L)
        assertEquals(120_000L, delay)
        assertNull(CommanderReplyQueue.nextDue(root, 60_000L))
        assertEquals(60_300L, CommanderReplyQueue.nextDelayMs(root, 0L))
        assertEquals("msg-429", CommanderReplyQueue.nextDue(root, 120_300L)?.optString("message_id"))
        assertTrue(CommanderReplyQueue.hasPending(root))
        CommanderReplyQueue.markAnswered(root, "msg-429", "reply-1", 130_000L)
        assertFalse(CommanderReplyQueue.hasPending(root))
        assertTrue(CommanderReplyQueue.isAnswered(root, "msg-429"))
    }

    @Test fun retryAfterSupportsSecondsAndHttpDate() {
        assertEquals(90_000L, ChatEngine.parseRetryAfterMillis("90", 1_000L))
        val date = java.time.ZonedDateTime.parse(
            "Mon, 23 Nov 2026 12:01:00 GMT",
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
        ).toInstant().toEpochMilli()
        assertEquals(60_000L, ChatEngine.parseRetryAfterMillis("Mon, 23 Nov 2026 12:01:00 GMT", date - 60_000L))
    }

    @Test fun legacyPendingFlagCanRecoverLatestUnansweredMessage() {
        val root = root()
        FileManager.init(root)
        FileManager.appendEvent(root, org.json.JSONObject()
            .put("id", "legacy-msg").put("type", "message").put("who", "commander").put("text", "Ответь"))
        OfflineQueue.markPending(root)
        assertEquals("legacy-msg", CommanderReplyQueue.recoverLatestUnanswered(root))
        assertEquals("legacy-msg", CommanderReplyQueue.nextDue(root, System.currentTimeMillis())?.optString("message_id"))
    }
}
