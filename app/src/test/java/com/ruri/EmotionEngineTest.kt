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
        val raw = "Я немного задумалась.\nEMOTION_DELTA: {\"joy\":-10,\"anger\":15}"
        val (visible, delta) = EmotionEngine.parseAndStrip(raw)
        assertEquals("Я немного задумалась.", visible)
        assertEquals(-10.0, delta?.getDouble("joy") ?: 0.0, 0.0)
        assertEquals(15.0, delta?.getDouble("anger") ?: 0.0, 0.0)
    }
}

class MoodLabelTest : EmotionEngineTestBase() {
    @Test fun moodLabelUsesPriorityRules() {
        assertEquals("раздражённая", EmotionEngine.moodLabel(JSONObject().put("anger", 61)))
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


class InitiativeGateEmotionTest : EmotionEngineTestBase() {
    @Test fun emotionCanMakeInitiativeDueEarlierOrDelayIt() {
        val now = System.currentTimeMillis()
        val lastSeen = now - 3L * EMOTION_TEST_HOUR_MS
        assertEquals(false, EmotionEngine.initiativeGate(root, lastSeen, now, false, 0L))

        EmotionEngine.applyDelta(root, JSONObject().put("boredom", 80.0), "test")
        assertEquals(true, EmotionEngine.initiativeGate(root, lastSeen, now, false, 0L))

        EmotionEngine.applyDelta(root, JSONObject().put("boredom", -80.0)
            .put("anger", 90.0).put("fatigue", 80.0), "test")
        assertEquals(false, EmotionEngine.initiativeGate(root, lastSeen, now, false, 0L))
    }

    @Test fun waitingForReplyAlwaysBlocksInitiative() {
        val now = System.currentTimeMillis()
        assertEquals(false, EmotionEngine.initiativeGate(
            root, now - 12L * EMOTION_TEST_HOUR_MS, now, true, 0L
        ))
    }
}

class LegacyEmotionMigrationTest : EmotionEngineTestBase() {
    @Test fun hurtAndCalmAreRemovedAndHurtSplitsIntoAngerAndSadness() {
        val config = JSONObject(FileManager.read(root, "core/emotions.json"))
        config.put("schema_version", 1)
        val channels = config.getJSONObject("channels")
        channels.put("hurt", JSONObject().put("min", 0).put("max", 100).put("base", 10).put("half_life_h", 24))
        channels.put("calm", JSONObject().put("min", 0).put("max", 100).put("base", 60).put("half_life_h", 6))
        FileManager.write(root, "core/emotions.json", config.toString(2))

        val state = JSONObject(FileManager.read(root, "memory/emotions.json"))
        val values = state.getJSONObject("values")
            .put("anger", 10.0)
            .put("sadness", 20.0)
            .put("hurt", 40.0)
            .put("calm", 75.0)
        state.put("values", values)
        FileManager.write(root, "memory/emotions.json", state.toString(2))

        EmotionEngine.ensure(root)

        val migratedConfig = JSONObject(FileManager.read(root, "core/emotions.json"))
        val migratedChannels = migratedConfig.getJSONObject("channels")
        val migratedValues = EmotionEngine.readValues(root)
        assertEquals(2, migratedConfig.getInt("schema_version"))
        assertFalse(migratedChannels.has("hurt"))
        assertFalse(migratedChannels.has("calm"))
        assertFalse(migratedValues.has("hurt"))
        assertFalse(migratedValues.has("calm"))
        assertEquals(30.0, migratedValues.getDouble("anger"), 0.0)
        assertEquals(40.0, migratedValues.getDouble("sadness"), 0.0)
    }
}

class EmotionOncePerMessageTest : EmotionEngineTestBase() {
    @Test fun repeatedCommanderMessageIdDoesNotApplyEmotionDeltaAgain() {
        val before = EmotionEngine.readValues(root)
        EmotionEngine.onCommanderMessage(root, "Привет, Рури", "commander-message-42")
        val afterFirst = EmotionEngine.readValues(root)

        assertEquals(before.getDouble("fatigue") + 3.0, afterFirst.getDouble("fatigue"), 0.0)
        assertEquals(before.getDouble("boredom") - 6.0, afterFirst.getDouble("boredom"), 0.0)
        assertEquals(before.getDouble("longing") - 10.0, afterFirst.getDouble("longing"), 0.0)

        EmotionEngine.onCommanderMessage(root, "Привет, Рури", "commander-message-42")
        val afterRetry = EmotionEngine.readValues(root)
        for (channel in listOf("fatigue", "boredom", "longing", "desire")) {
            assertEquals(afterFirst.getDouble(channel), afterRetry.getDouble(channel), 0.0)
        }
    }
}

class ReplyReliefOnceTest : EmotionEngineTestBase() {
    @Test fun repeatedCommanderReplyIdDoesNotApplyReliefAgain() {
        EmotionEngine.applyDelta(
            root,
            JSONObject().put("anger", 20.0).put("sadness", 20.0).put("longing", 20.0),
            "test_seed"
        )
        val before = EmotionEngine.readValues(root)
        EmotionEngine.onCommanderReply(root, "commander-reply-42")
        val afterFirst = EmotionEngine.readValues(root)

        assertEquals(before.getDouble("anger") - 8.0, afterFirst.getDouble("anger"), 0.0)
        assertEquals(before.getDouble("sadness") - 6.0, afterFirst.getDouble("sadness"), 0.0)
        assertEquals(before.getDouble("joy") + 5.0, afterFirst.getDouble("joy"), 0.0)
        assertEquals(before.getDouble("longing") - 20.0, afterFirst.getDouble("longing"), 0.0)

        EmotionEngine.onCommanderReply(root, "commander-reply-42")
        val afterRetry = EmotionEngine.readValues(root)
        for (channel in listOf("anger", "sadness", "joy", "longing")) {
            assertEquals(afterFirst.getDouble(channel), afterRetry.getDouble(channel), 0.0)
        }
    }
}

class FatigueResetV1Test : EmotionEngineTestBase() {
    @Test fun fatigueIsResetTo50OnceAndNotOnLaterEnsureCalls() {
        val config = JSONObject(FileManager.read(root, "core/emotions.json"))
            .put("fatigue_reset_v1", false)
        FileManager.write(root, "core/emotions.json", config.toString(2))
        val state = JSONObject(FileManager.read(root, "memory/emotions.json"))
        state.getJSONObject("values").put("fatigue", 91.0)
        FileManager.write(root, "memory/emotions.json", state.toString(2))

        EmotionEngine.ensure(root)

        assertEquals(50.0, EmotionEngine.readValues(root).getDouble("fatigue"), 0.0)
        assertTrue(JSONObject(FileManager.read(root, "core/emotions.json")).optBoolean("fatigue_reset_v1"))

        EmotionEngine.applyDelta(root, JSONObject().put("fatigue", 17.0), "test_after_reset")
        EmotionEngine.ensure(root)
        assertEquals(67.0, EmotionEngine.readValues(root).getDouble("fatigue"), 0.0)
    }
}

