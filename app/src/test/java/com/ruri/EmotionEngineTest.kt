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


class EmotionThresholdsTest : EmotionEngineTestBase() {
    @Test fun neutralEmotionValuesPreserveExistingSchedule() {
        val th = EmotionEngine.thresholds(root)
        assertEquals(3.5, th.getDouble("initiative_h"), 0.0001)
        assertEquals(3.0, th.getDouble("cooldown_h"), 0.0001)
        assertEquals(6.0, th.getDouble("reflection_h"), 0.0001)
    }

    @Test fun boredomAndAffectionLowerInitiativeThreshold() {
        EmotionEngine.applyDelta(root, JSONObject().put("boredom", 60.0).put("affection", 30.0), "test")
        val th = EmotionEngine.thresholds(root)
        assertTrue(th.getDouble("initiative_h") < 3.5)
        assertTrue(th.getDouble("cooldown_h") < 3.0)
    }

    @Test fun hurtAndFatigueRaiseInitiativeThreshold() {
        EmotionEngine.applyDelta(root, JSONObject().put("hurt", 70.0).put("fatigue", 60.0), "test")
        val th = EmotionEngine.thresholds(root)
        assertTrue(th.getDouble("initiative_h") > 3.5)
        assertTrue(th.getDouble("cooldown_h") > 3.0)
    }

    @Test fun interestAndBoredomShortenReflectionInterval() {
        EmotionEngine.applyDelta(root, JSONObject().put("interest", 50.0).put("boredom", 40.0), "test")
        assertTrue(EmotionEngine.thresholds(root).getDouble("reflection_h") < 6.0)
    }
}

class InitiativeGateEmotionTest : EmotionEngineTestBase() {
    @Test fun emotionCanMakeInitiativeDueEarlierOrDelayIt() {
        val now = System.currentTimeMillis()
        val lastSeen = now - 3L * EMOTION_TEST_HOUR_MS
        assertEquals(false, EmotionEngine.initiativeGate(root, lastSeen, now, false, 0L))

        EmotionEngine.applyDelta(root, JSONObject().put("boredom", 80.0), "test")
        assertEquals(true, EmotionEngine.initiativeGate(root, lastSeen, now, false, 0L))

        EmotionEngine.applyDelta(root, JSONObject().put("boredom", -80.0)
            .put("hurt", 90.0).put("fatigue", 80.0), "test")
        assertEquals(false, EmotionEngine.initiativeGate(root, lastSeen, now, false, 0L))
    }

    @Test fun waitingForReplyAlwaysBlocksInitiative() {
        val now = System.currentTimeMillis()
        assertEquals(false, EmotionEngine.initiativeGate(
            root, now - 12L * EMOTION_TEST_HOUR_MS, now, true, 0L
        ))
    }
}

class ThresholdsMergeTest : EmotionEngineTestBase() {
    @Test fun oldEmotionConfigReceivesMissingInitiativeWeights() {
        val config = JSONObject(FileManager.read(root, "core/emotions.json"))
        val drift = config.getJSONObject("drift")
        val newKeys = listOf(
            "base_initiative_h", "base_cooldown_h", "base_reflection_h",
            "w_hurt_init", "w_fatigue_init", "w_boredom_init",
            "w_affection_init", "w_anxiety_init", "w_interest_init"
        )
        newKeys.forEach { drift.remove(it) }
        FileManager.write(root, "core/emotions.json", config.toString(2))

        EmotionEngine.ensure(root)

        val migrated = JSONObject(FileManager.read(root, "core/emotions.json")).getJSONObject("drift")
        assertEquals(3.5, migrated.getDouble("base_initiative_h"), 0.0)
        assertEquals(3.0, migrated.getDouble("base_cooldown_h"), 0.0)
        assertEquals(6.0, migrated.getDouble("base_reflection_h"), 0.0)
        assertEquals(2.0, migrated.getDouble("w_hurt_init"), 0.0)
        assertEquals(2.0, migrated.getDouble("w_fatigue_init"), 0.0)
        assertEquals(1.5, migrated.getDouble("w_boredom_init"), 0.0)
        assertEquals(1.0, migrated.getDouble("w_affection_init"), 0.0)
        assertEquals(1.0, migrated.getDouble("w_anxiety_init"), 0.0)
        assertEquals(0.5, migrated.getDouble("w_interest_init"), 0.0)
    }
}
