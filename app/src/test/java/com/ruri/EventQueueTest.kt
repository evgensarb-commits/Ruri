package com.ruri

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EventQueueTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("ru-r1-event-queue").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun repeatedReasonsAreCoalescedWithoutDroppingOccurrenceCount() {
        val firstId = EventQueue.enqueue(root, "commander_message")
        val secondId = EventQueue.enqueue(root, "commander_message")

        assertEquals(firstId, secondId)
        val pending = EventQueue.snapshot(root)
        assertEquals(1, pending.size)
        assertEquals(2, pending.single().optInt("occurrences"))
        assertEquals(2L, pending.single().optLong("revision"))
    }

    @Test
    fun acknowledgementKeepsEventsUpdatedDuringAnActiveCycle() {
        EventQueue.enqueue(root, "phone_notification")
        val snapshot = EventQueue.snapshot(root)

        EventQueue.enqueue(root, "phone_notification")
        EventQueue.acknowledge(root, snapshot)

        val remaining = EventQueue.snapshot(root)
        assertEquals(1, remaining.size)
        assertEquals(2, remaining.single().optInt("occurrences"))
        assertEquals(2L, remaining.single().optLong("revision"))

        EventQueue.acknowledge(root, remaining)
        assertTrue(EventQueue.snapshot(root).isEmpty())
    }

    @Test
    fun differentReasonsRemainSeparate() {
        EventQueue.enqueue(root, "commander_message")
        EventQueue.enqueue(root, "phone_notification")

        val pending = EventQueue.snapshot(root)
        assertEquals(2, pending.size)
        assertFalse(pending[0].optString("reason") == pending[1].optString("reason"))
    }

    @Test
    fun l0AppendsEventsAndRejectsOrdinaryOverwrite() {
        val stored = JSONObject(
            FileManager.appendEvent(
                root,
                JSONObject().put("type", "test_event").put("source", "unit_test")
            )
        )
        assertTrue(stored.optString("id").isNotBlank())
        assertTrue(stored.optLong("timestamp") > 0L)

        val l0 = File(root, "memory/L0_raw.jsonl")
        val before = l0.readText()
        assertThrows(IllegalArgumentException::class.java) {
            FileManager.write(root, "memory/L0_raw.jsonl", "{\"corrupt\":true}")
        }
        assertEquals(before, l0.readText())
        assertEquals(1, before.lineSequence().count { it.isNotBlank() })
    }

    @Test
    fun boundedLogTailReturnsNewestCompleteUtf8Lines() {
        repeat(5) { index ->
            FileManager.appendEvent(
                root,
                JSONObject().put("type", "tail_test").put("text", "событие-$index")
            )
        }

        val tail = FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 2)
        assertEquals(2, tail.size)
        assertTrue(tail[0].contains("событие-3"))
        assertTrue(tail[1].contains("событие-4"))
    }

    @Test
    fun l0BatchUsesByteCursorAndReturnsOnlyCompleteRecords() {
        FileManager.appendEvent(root, JSONObject().put("type", "cursor_test").put("text", "первое"))
        val first = FileManager.readL0Batch(root, 0L, maxRecords = 1)
        assertEquals(1, first.events.size)
        assertEquals(1, first.recordsRead)
        assertTrue(first.nextOffset > 0L)
        assertFalse(first.incompleteTail)

        FileManager.appendEvent(root, JSONObject().put("type", "cursor_test").put("text", "второе"))
        val second = FileManager.readL0Batch(root, first.nextOffset, maxRecords = 10)
        assertEquals(1, second.events.size)
        assertEquals("второе", second.events.single().optString("text"))
        assertFalse(second.hasMore)
    }

    @Test
    fun incompleteFinalL0LineIsNotConsumedUntilItIsTerminated() {
        val l0 = File(root, "memory/L0_raw.jsonl")
        l0.parentFile?.mkdirs()
        l0.writeText("{\"id\":\"partial\",\"type\":\"incomplete\"", Charsets.UTF_8)
        val first = FileManager.readL0Batch(root, 0L)
        assertEquals(0, first.events.size)
        assertTrue(first.incompleteTail)
        assertEquals(0L, first.nextOffset)

        FileManager.appendEvent(root, JSONObject().put("type", "cursor_test"))
        val second = FileManager.readL0Batch(root, 0L)
        assertTrue(second.events.any { it.optString("type") == "cursor_test" })
        assertTrue(second.malformedLines >= 1)
    }

    @Test
    fun eventIdCursorCanBeMigratedToByteOffset() {
        val stored = JSONObject(FileManager.appendEvent(root, JSONObject().put("type", "cursor_test")))
        val offset = FileManager.findL0EventEndOffset(root, stored.optString("id"))
        assertTrue(offset != null && offset > 0L)
        val next = FileManager.readL0Batch(root, offset!!)
        assertTrue(next.events.isEmpty())
    }

    @Test
    fun eventQueueWithValidJsonButMissingPendingArrayIsBackedUp() {
        File(root, "memory").mkdirs()
        val queueFile = File(root, "memory/pending_events.json")
        queueFile.writeText("{\"schema_version\":1,\"wrong_key\":[]}", Charsets.UTF_8)

        assertTrue(EventQueue.snapshot(root).isEmpty())
        EventQueue.enqueue(root, "commander_message")
        assertEquals(1, EventQueue.snapshot(root).size)
        val backups = File(root, "backup").listFiles()?.filter { it.name.startsWith("pending_events_corrupt_") }.orEmpty()
        assertTrue(backups.isNotEmpty())
        assertTrue(backups.any { it.readText(Charsets.UTF_8).contains("wrong_key") })
    }

    @Test
    fun l0BatchWalksSeveralPagesWithoutSkippingOrRepeatingEvents() {
        repeat(7) { index ->
            FileManager.appendEvent(root, JSONObject().put("type", "paged").put("index", index))
        }
        var offset = 0L
        val indices = ArrayList<Int>()
        var hasMore: Boolean
        do {
            val batch = FileManager.readL0Batch(root, offset, maxRecords = 2)
            indices.addAll(batch.events.map { it.optInt("index") })
            assertTrue(batch.nextOffset >= offset)
            offset = batch.nextOffset
            hasMore = batch.hasMore
        } while (hasMore)

        assertEquals((0..6).toList(), indices)
    }

    @Test
    fun lifeMemoryInitializationCreatesIncrementalFactCheckpointAndFullContextPolicy() {
        FileManager.init(root)
        assertTrue(FileManager.exists(root, "memory/fact_extraction_pending.json"))
        val pending = JSONObject(FileManager.read(root, "memory/fact_extraction_pending.json"))
        assertFalse(pending.optBoolean("initialized", true))

        val permissions = JSONObject(FileManager.read(root, "core/permissions.json"))
        val policy = permissions.optJSONObject("remote_model_context")
        assertTrue(policy?.optBoolean("enabled", false) == true)
        val scopes = policy?.optJSONArray("allowed_scopes")
        assertTrue((0 until (scopes?.length() ?: 0)).any { scopes?.optString(it) == "notification_contents" })
        assertTrue(policy?.optBoolean("include_sensitive_context", false) == true)
        assertFalse(policy?.optBoolean("include_authentication_credentials", true) == true)
        assertTrue(policy?.optBoolean("exclude_authentication_credentials", false) == true)
        assertEquals("commander_requested_full_context_2026-11-18", policy?.optString("consent_record"))
    }

    @Test
    fun pathTraversalIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FileManager.write(root, "../outside.json", "{}")
        }
    }
}
