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
    fun sameTimestampCommanderMessageStillEndsWaitingForReply() {
        val state = JSONObject()
            .put("last_seen_commander", 123456L)
            .put("waiting_for_reply", true)
            .put("ignored_count", 3)
            .put("offended_until", 999999L)
        val message = JSONObject()
            .put("id", "commander-same-ms")
            .put("type", "message")
            .put("who", "commander")
            .put("timestamp", 234567L)

        assertTrue(LifeStateEngine.applyCommanderActivity(state, listOf(message)))
        assertEquals(234567L, state.optLong("last_seen_commander"))
        assertEquals("commander-same-ms", state.optString("last_commander_message_event_id"))
        assertFalse(state.optBoolean("waiting_for_reply"))
        assertEquals(0, state.optInt("ignored_count"))
        assertEquals(0L, state.optLong("offended_until"))
    }

    @Test
    fun unrelatedEventsDoNotResetCommanderWaitingState() {
        val state = JSONObject()
            .put("last_seen_commander", 123456L)
            .put("waiting_for_reply", true)
            .put("ignored_count", 2)
        val event = JSONObject().put("type", "phone_notification").put("timestamp", 999999L)

        assertFalse(LifeStateEngine.applyCommanderActivity(state, listOf(event)))
        assertTrue(state.optBoolean("waiting_for_reply"))
        assertEquals(2, state.optInt("ignored_count"))
    }

    @Test
    fun selfModelStoresLatestStateWithoutLosingIdentityOrCapabilities() {
        val existing = JSONObject()
            .put("identity", "Рури")
            .put("project", "RU-R1")
            .put("known_capabilities", JSONArray().put("chat").put("local_memory"))
        FileManager.write(root, "memory/self_model.json", existing.toString())
        val state = JSONObject()
            .put("mood", "собранная")
            .put("energy", 6)
            .put("current_focus", "Восстановление памяти")
            .put("last_seen_commander", 456789L)
            .put("last_processed_event_offset", 128L)
            .put("waiting_for_reply", false)
            .put("needs", JSONObject().put("live", 0.4).put("love_commander", 0.7))
            .put("active_goals", JSONArray().put(JSONObject().put("id", "ru-r1")))
            .put("active_tasks", JSONArray().put(JSONObject().put("id", "memory-check")))

        LifeStateEngine.updateSelfModel(root, state, "unit_test", 987654L)

        val saved = JSONObject(FileManager.read(root, "memory/self_model.json"))
        assertEquals("Рури", saved.optString("identity"))
        assertEquals("chat", saved.getJSONArray("known_capabilities").getString(0))
        assertEquals("Восстановление памяти", saved.optString("current_focus"))
        assertEquals(987654L, saved.optLong("updated_at"))
        assertEquals("собранная", saved.getJSONObject("current_state").optString("mood"))
        assertEquals(128L, saved.getJSONObject("current_state").optLong("last_processed_event_offset"))
        assertEquals(1, saved.getJSONObject("current_state").getJSONArray("active_goals").length())
    }

    @Test
    fun damagedL4RestoresActiveTasksFromDurableGoalStore() {
        val goals = JSONObject()
            .put("schema_version", 2)
            .put("active", JSONArray().put(JSONObject().put("id", "project-ruri")))
            .put("completed", JSONArray())
            .put(
                "active_tasks",
                JSONArray().put(
                    JSONObject()
                        .put("id", "task-memory-audit")
                        .put("title", "Проверить целостность L0")
                        .put("status", "in_progress")
                )
            )
            .put("completed_tasks", JSONArray())
        FileManager.write(root, "memory/goals.json", goals.toString(2))

        val recovered = LifeStateEngine.recoverStateFromHistory(root, "{damaged")
        val tasks = recovered.optJSONArray("active_tasks") ?: JSONArray()

        assertEquals(1, tasks.length())
        assertEquals("task-memory-audit", tasks.getJSONObject(0).optString("id"))
        assertEquals("in_progress", tasks.getJSONObject(0).optString("status"))
        assertEquals(1, recovered.optJSONArray("active_goals")?.length())
    }

    @Test
    fun fileManagerMigratesLegacyGoalStoreWithoutDroppingExistingGoals() {
        val legacy = JSONObject()
            .put("schema_version", 1)
            .put("active", JSONArray().put(JSONObject().put("id", "legacy-goal")))
            .put("completed", JSONArray())
        FileManager.write(root, "memory/goals.json", legacy.toString())

        FileManager.init(root)

        val migrated = JSONObject(FileManager.read(root, "memory/goals.json"))
        assertEquals(2, migrated.optInt("schema_version"))
        assertEquals("legacy-goal", migrated.getJSONArray("active").getJSONObject(0).optString("id"))
        assertTrue(migrated.has("active_tasks"))
        assertTrue(migrated.has("completed_tasks"))
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
                .put("text", "Нужно вернуться к нему позже").put("timestamp", firstTs + 1000L)
        )
        FileManager.write(
            root,
            "memory/goals.json",
            JSONObject().put("active", JSONArray().put(JSONObject().put("id", "observatory"))).toString()
        )
        val damaged = "{ not valid L4 JSON"
        FileManager.write(root, "memory/L4_state.json", damaged)

        val recovered = LifeStateEngine.recoverStateFromHistory(root, damaged)

        assertEquals(firstTs + 1000L, recovered.optLong("last_seen_commander"))
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
