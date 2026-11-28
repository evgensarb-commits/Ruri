package com.ruri

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CommanderPresenceRegressionTest {
    @Test
    fun incomingMessageUpdatesLastSeenBeforeReplyIsSaved() {
        val root = Files.createTempDirectory("ruri-commander-presence").toFile()
        try {
            FileManager.init(root)
            val timestamp = 1_800_000_000_000L
            FileManager.appendEvent(root, JSONObject()
                .put("id", "incoming-now")
                .put("type", "message")
                .put("who", "commander")
                .put("text", "Я здесь")
                .put("timestamp", timestamp))
            ChatEngine.updateLastSeenCommander(root, "incoming-now")
            val state = JSONObject(FileManager.read(root, "memory/L4_state.json"))
            assertEquals(timestamp, state.optLong("last_seen_commander"))
        } finally {
            root.deleteRecursively()
        }
    }
}
