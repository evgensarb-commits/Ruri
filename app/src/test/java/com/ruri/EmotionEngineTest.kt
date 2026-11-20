package com.ruri

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val EMOTION_TEST_HOUR_MS = 3_600_000L

abstract class EmotionEngineTestBase {
    protected lateinit var root: File

    @Before fun setUpEmotionRoot() {
        root = Files.createTempDirectory("ruri-emotion-engine").toFile()
        FileManager.init(root)
        EmotionEngine.ensure(root)
    }

    @After fun tearDownEmotionRoot() {
        root.deleteRecursively()
    }
}

class EmotionClampTest : EmotionEngineTestBase() {
    @Test fun desireCannotFallBelowConfiguredMinimum() {
        val result = EmotionEngine.applyDelta(
            root, JSONObject().put("desire", -100.0), "test"
        )
        assertEquals(30.0, result.getJSONObject("after").getDouble("desire"), 0.0)
    }
}

class SignedDeltaTest : EmotionEngineTestBase() {
    @Test fun negativeAndPositiveDeltasKeepTheirSigns() {
        val result = EmotionEngine.applyDelta(
            root, JSONObject().put("joy", -15.0).put("affection", 12.0), "test"
        )
        assertEquals(-15.0, result.getJSONObject("delta").getDouble("joy"), 0.0)
        assertEquals(12.0, result.getJSONObject("delta").getDouble("affection"), 0.0)
        assertEquals(35.0, result.getJSONObject("after").getDouble("joy"), 0.0)
        assertEquals(62.0, result.getJSONObject("after").getDouble("affection"), 0.0)
    }
}

class DecayTest : EmotionEngineTestBase() {
    @Test fun valuesMoveTowardTheirConfiguredBaseByHalfLife() {
        EmotionEngine.applyDelta(root, JSONObject().put("joy", 40.0), "test")
        val now = System.currentTimeMillis() + 6L * EMOTION_TEST_HOUR_MS
        EmotionEngine.decay(root, now)
        assertEquals(70.0, EmotionEngine.readValues(root).getDouble("joy"), 0.1)
    }
}

class AutonomousDriftTest : EmotionEngineTestBase() {
    @Test fun boredomGrowsDuringAnIdleHour() {
        val now = System.currentTimeMillis()
        val state = JSONObject(FileManager.read(root, "memory/emotions.json"))
            .put("updated_at", now - EMOTION_TEST_HOUR_MS)
            .put("last_tick_at", now - EMOTION_TEST_HOUR_MS)
        FileManager.write(root, "memory/emotions.json", state.toString())
        EmotionEngine.tick(root, now, 0L, now - 1L)
        assertEquals(22.0, EmotionEngine.readValues(root).getDouble("boredom"), 0.1)
    }
}

class EmotionLineStripTest : EmotionEngineTestBase() {
    @Test fun serviceLineIsRemovedFromVisibleReply() {
        val raw = "Я немного задумалась.\nEMOTION_DELTA: {\"joy\":-10,\"hurt\":15}"
        val (visible, delta) = EmotionEngine.parseAndStrip(raw)
        assertEquals("Я немного задумалась.", visible)
        assertEquals(-10.0, delta?.getDouble("joy") ?: 0.0, 0.0)
        assertEquals(15.0, delta?.getDouble("hurt") ?: 0.0, 0.0)
    }
}

class MoodLabelTest : EmotionEngineTestBase() {
    @Test fun moodLabelUsesPriorityRules() {
        assertEquals("обиженная", EmotionEngine.moodLabel(JSONObject().put("hurt", 61)))
        assertEquals("томная", EmotionEngine.moodLabel(JSONObject().put("desire", 61)))
        assertEquals("собранная", EmotionEngine.moodLabel(JSONObject()))
    }
}

class EmotionEpisodeTagsTest : EmotionEngineTestBase() {
    @Test fun queryEmotionBoostsMatchingDiaryEpisode() {
        val now = System.currentTimeMillis()
        val entries = JSONArray()
            .put(JSONObject()
                .put("ts", now)
                .put("text", "кофе утром")
                .put("emotion_after", JSONObject().put("joy", 80)))
            .put(JSONObject()
                .put("ts", now)
                .put("text", "кофе вечером")
                .put("emotion_after", JSONObject().put("joy", 20)))
        FileManager.write(root, "memory/L2_diary.json", JSONObject().put("entries", entries).toString())
        val recalled = MemoryRetriever.recall(root, "рад кофе", limit = 5)
        assertTrue(recalled.indexOf("кофе утром") in 0 until recalled.indexOf("кофе вечером"))
    }
}
