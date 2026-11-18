package com.ruri

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LifeStateRecoveryTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("ru-r1-life-recovery").toFile()
        FileManager.init(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun damagedL4IsBackedUpAndRebuiltFromL0WithoutSkippingHistory() {
        val firstTs = 1_790_000_000_000L
        FileManager.appendEvent(
            root,
            JSONObject().put("type", "message").put("who", "commander")
                .put("text", "Мы обсуждали проект обсерватории").put("timestamp", firstTs)
        )
        FileManager.appendEvent(
            root,
            JSONObject().put("type", "message").put("who", "commander")
                .put("text", "Нужно вернуться к нему позже").put("timestamp", firstTs)
        )
        FileManager.write(
            root,
            "memory/goals.json",
            JSONObject().put("active", JSONArray().put(JSONObject().put("id", "observatory"))).toString()
        )
        val damaged = "{ not valid L4 JSON"
        FileManager.write(root, "memory/L4_state.json", damaged)

        val recovered = LifeStateEngine.recoverStateFromHistory(root, damaged)

        assertEquals(firstTs, recovered.optLong("last_seen_commander"))
        assertEquals(0L, recovered.optLong("last_processed_event_offset"))
        assertEquals(1, recovered.optJSONArray("active_goals")?.length())
        assertTrue(recovered.optString("current_focus").contains("Восстановление"))
        assertEquals(damaged, File(root, "backup").listFiles()
            ?.firstOrNull { it.name.startsWith("memory_L4_state.json.corrupt_") }
            ?.readText(Charsets.UTF_8))
        assertFalse(FileManager.read(root, "memory/L0_raw.jsonl").isBlank())
        assertEquals(2, FileManager.readL0Batch(root, 0L).events.size)
    }
}
