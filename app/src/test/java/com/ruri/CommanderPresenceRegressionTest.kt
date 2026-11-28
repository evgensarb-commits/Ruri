package com.ruri

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CommanderPresenceRegressionTest {
    @Test
    fun latestCommanderTimestampComesFromL0BeforeL4CatchesUp() {
        val root = Files.createTempDirectory("ruri-commander-l0-time").toFile()
        try {
            FileManager.init(root)
            FileManager.appendEvent(root, JSONObject()
                .put("type", "message").put("who", "commander")
                .put("text", "Первое").put("timestamp", 1_800_000_000_000L))
            FileManager.appendEvent(root, JSONObject()
                .put("type", "message").put("who", "ruri")
                .put("text", "Ответ").put("timestamp", 1_800_000_000_500L))
            FileManager.appendEvent(root, JSONObject()
                .put("type", "message").put("who", "commander")
                .put("text", "Я снова здесь").put("timestamp", 1_800_000_001_000L))
            assertEquals(1_800_000_001_000L, ChatEngine.latestCommanderMessageTimestamp(root))
        } finally {
            root.deleteRecursively()
        }
    }

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
