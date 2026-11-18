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
    fun pathTraversalIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FileManager.write(root, "../outside.json", "{}")
        }
    }
}
