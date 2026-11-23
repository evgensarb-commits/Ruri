package com.ruri

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun bTestRoot(prefix: String): File = Files.createTempDirectory(prefix).toFile()

class TurnInFlightTest {
    @Test fun flagIsSetAndClearedDurably() {
        val root = bTestRoot("ruri-in-flight")
        try {
            FileManager.init(root)
            ChatEngine.setTurnInFlight(root, true)
            assertTrue(JSONObject(FileManager.read(root, "memory/L4_state.json")).optBoolean("turn_in_flight"))
            ChatEngine.setTurnInFlight(root, false)
            assertFalse(JSONObject(FileManager.read(root, "memory/L4_state.json")).optBoolean("turn_in_flight"))
        } finally { root.deleteRecursively() }
    }
}

class ReplyMessageIdTest {
    @Test fun finalReplyReturnsItsPersistedL0Id() {
        val root = bTestRoot("ruri-reply-id")
        try {
            FileManager.init(root)
            val id = ChatEngine.appendFinalReply(root, "Ответ с id")
            assertTrue(id.isNotBlank())
            val event = FileManager.readLastLines(root, "memory/L0_raw.jsonl", 10)
                .map { JSONObject(it) }
                .last { it.optString("who") == "ruri" && it.optString("type") == "message" }
            assertEquals(id, event.optString("id"))
        } finally { root.deleteRecursively() }
    }
}

class QuietHoursOnSuccessTest {
    @Test fun commanderActivityDoesNotAdvanceQuietHoursBeforeReply() {
        val state = JSONObject()
            .put("last_seen_commander", 100L)
            .put("waiting_for_reply", true)
            .put("ignored_count", 2)
        val event = JSONObject().put("id", "c-1")
            .put("type", "message").put("who", "commander").put("ts", 900L)
        assertTrue(LifeStateEngine.applyCommanderActivity(state, listOf(event)))
        assertEquals(100L, state.optLong("last_seen_commander"))
        assertFalse(state.optBoolean("waiting_for_reply"))
        assertEquals(0, state.optInt("ignored_count"))
    }

    @Test fun successfulReplyAdvancesQuietHoursAndWritesReceipts() {
        val root = bTestRoot("ruri-quiet-success")
        try {
            FileManager.init(root)
            FileManager.appendEvent(root, JSONObject().put("id", "commander-1")
                .put("type", "message").put("who", "commander").put("text", "Привет").put("ts", 123456L))
            ChatEngine.appendFinalReply(root, "Ответ")
            val state = JSONObject(FileManager.read(root, "memory/L4_state.json"))
            assertEquals(123456L, state.optLong("last_seen_commander"))
            val receipts = JSONObject(Receipts.export(root)).getJSONObject("commander-1")
            assertTrue(receipts.optBoolean("replied_by_ruri"))
            assertTrue(receipts.optBoolean("read_by_ruri"))
        } finally { root.deleteRecursively() }
    }
}

class ReceiptsAndTombstoneTest {
    @Test fun receiptFieldsPersistAndTombstonesAreIdempotent() {
        val root = bTestRoot("ruri-receipt-tombstone")
        try {
            FileManager.init(root)
            Receipts.delivered(root, "message-1")
            Receipts.readByRuri(root, "message-1")
            Receipts.repliedByRuri(root, "message-1")
            val rec = JSONObject(Receipts.export(root)).getJSONObject("message-1")
            assertTrue(rec.optBoolean("delivered"))
            assertTrue(rec.optBoolean("read_by_ruri"))
            assertTrue(rec.optBoolean("replied_by_ruri"))

            Tombstone.add(root, "message-1")
            Tombstone.add(root, "message-1")
            assertTrue(Tombstone.isDeleted(root, "message-1"))
            assertEquals(1, Tombstone.ids(root).size)
            val deletionEvents = FileManager.readLastLines(root, "memory/L0_raw.jsonl", 50)
                .map { JSONObject(it) }.count { it.optString("type") == "message_deleted" }
            assertEquals(1, deletionEvents)
        } finally { root.deleteRecursively() }
    }

    @Test fun deletedMessageIsExcludedFromChatHistoryPredicate() {
        val root = bTestRoot("ruri-history-tombstone")
        try {
            FileManager.init(root)
            val event = JSONObject().put("id", "gone-1").put("type", "message").put("who", "commander")
            assertTrue(ChatEngine.shouldIncludeInChatHistory(root, event))
            Tombstone.add(root, "gone-1")
            assertFalse(ChatEngine.shouldIncludeInChatHistory(root, event))
        } finally { root.deleteRecursively() }
    }

    @Test fun deletedMessagesAndDiaryEntriesAreNotRecalled() {
        val root = bTestRoot("ruri-recall-tombstone")
        try {
            FileManager.init(root)
            val oldId = "old-secret-id"
            FileManager.appendEvent(root, JSONObject().put("id", oldId)
                .put("type", "message").put("who", "commander")
                .put("text", "секретная обсерватория на марсе"))
            repeat(15) { i ->
                FileManager.appendEvent(root, JSONObject().put("type", "message")
                    .put("who", "commander").put("text", "обычный разговор номер $i"))
            }
            FileManager.write(root, "memory/L2_diary.json", JSONObject().put("entries", JSONArray()
                .put(JSONObject().put("id", "diary-gone").put("text", "секретная обсерватория заметка"))).toString())
            Tombstone.add(root, oldId)
            Tombstone.add(root, "diary-gone")
            val recalled = MemoryRetriever.recall(root, "секретная обсерватория марсе заметка", limit = 10)
            assertFalse(recalled.contains("секретная обсерватория"))
        } finally { root.deleteRecursively() }
    }
}

class TimestampNormalizationTest {
    @Test fun timestampIsPromotedToTsAndTsIsCanonical() {
        val root = bTestRoot("ruri-ts-normalize")
        try {
            FileManager.init(root)
            FileManager.appendEvent(root, JSONObject().put("type", "legacy").put("timestamp", 111L))
            FileManager.appendEvent(root, JSONObject().put("type", "canonical").put("ts", 222L).put("timestamp", 333L))
            FileManager.appendEvent(root, JSONObject().put("type", "now"))
            val events = FileManager.readLastLines(root, "memory/L0_raw.jsonl", 10).map { JSONObject(it) }
            val legacy = events.first { it.optString("type") == "legacy" }
            val canonical = events.first { it.optString("type") == "canonical" }
            val now = events.first { it.optString("type") == "now" }
            assertEquals(111L, legacy.optLong("ts"))
            assertEquals(111L, legacy.optLong("timestamp"))
            assertEquals(222L, canonical.optLong("ts"))
            assertEquals(222L, canonical.optLong("timestamp"))
            assertTrue(now.optLong("ts") > 0L)
        } finally { root.deleteRecursively() }
    }
}

class L0FullEventFilterTest {
    @Test fun errorFilterMatchesErrorTypesAndReturnsFullEvent() {
        val root = bTestRoot("ruri-l0-full-event")
        try {
            FileManager.init(root)
            FileManager.appendEvent(root, JSONObject().put("type", "thinker_error")
                .put("text", "test failure").put("custom_field", "full-data"))
            val items = FileManager.readLastEvents(root, "error", 100)
            assertEquals(1, items.length())
            val event = items.getJSONObject(0).getJSONObject("event")
            assertEquals("full-data", event.optString("custom_field"))
            assertEquals("thinker_error", event.optString("type"))
            assertNotNull(event.optString("id"))
        } finally { root.deleteRecursively() }
    }

    @Test fun factsExtractedIsFilterable() {
        val root = bTestRoot("ruri-l0-facts")
        try {
            FileManager.init(root)
            FileManager.appendEvent(root, JSONObject().put("type", "facts_extracted"))
            assertEquals(1, FileManager.readLastEvents(root, "facts_extracted", 100).length())
        } finally { root.deleteRecursively() }
    }
}

class DiaryDedupTest {
    @Test fun repeatedRecentThoughtIsSkippedButNewThoughtIsKept() {
        val entries = JSONArray().put(JSONObject().put("kind", "thought").put("text", "Повторная   мысль"))
        assertFalse(Thinker.shouldAppendDiaryEntry(entries, "повторная мысль"))
        assertTrue(Thinker.shouldAppendDiaryEntry(entries, "другая мысль"))
    }
}

class CapabilitiesHandsTest {
    @Test fun capabilitiesSeparateToolsFromLocalAutonomy() {
        val root = bTestRoot("ruri-capabilities-hands")
        try {
            FileManager.init(root)
            val prompt = Capabilities.bodySection(root)
            assertTrue(prompt.contains("РУКИ 1: ВНЕШНИЕ ИНСТРУМЕНТЫ"))
            assertTrue(prompt.contains("РУКИ 2: ЛОКАЛЬНАЯ АВТОНОМИЯ"))
            assertTrue(prompt.contains("tool_result"))
            assertTrue(prompt.contains("один JSON-объект"))
        } finally { root.deleteRecursively() }
    }
class L4AtomicUpdateTest {
    @Test fun concurrentFieldUpdatesAreNotLost() {
        val root = bTestRoot("ruri-l4-atomic")
        try {
            FileManager.init(root)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
            val tasks = (0 until 8).map {
                pool.submit {
                    repeat(100) {
                        FileManager.updateL4State(root) { state ->
                            state.put("atomic_counter", state.optInt("atomic_counter", 0) + 1)
                        }
                    }
                }
            }
            tasks.forEach { it.get() }
            pool.shutdown()
            assertEquals(800, JSONObject(FileManager.read(root, "memory/L4_state.json")).optInt("atomic_counter"))
        } finally { root.deleteRecursively() }
    }

    @Test fun malformedL4IsPreservedWhenAnAtomicUpdateIsAttempted() {
        val root = bTestRoot("ruri-l4-corrupt")
        try {
            FileManager.init(root)
            val file = File(root, "memory/L4_state.json")
            file.writeText("{broken", Charsets.UTF_8)
            val result = runCatching { FileManager.updateL4State(root) { it.put("onboarding_done", true) } }
            assertTrue(result.isFailure)
            assertEquals("{broken", file.readText(Charsets.UTF_8))
        } finally { root.deleteRecursively() }
    }
}

}
