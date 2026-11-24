package com.ruri

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

private fun needsTestRoot(): java.io.File {
    val root = Files.createTempDirectory("ruri-needs").toFile()
    FileManager.init(root)
    EmotionEngine.ensure(root)
    FileManager.write(root, "memory/L4_state.json", "{}")
    return root
}

private fun setEmotionValues(root: java.io.File, fatigue: Double = 0.0, boredom: Double = 0.0,
                             longing: Double = 0.0, updatedAt: Long = System.currentTimeMillis(),
                             lastTickAt: Long = updatedAt) {
    val values = EmotionEngine.readValues(root)
        .put("fatigue", fatigue).put("boredom", boredom).put("longing", longing)
    FileManager.write(root, "memory/emotions.json", JSONObject()
        .put("values", values).put("updated_at", updatedAt)
        .put("last_tick_at", lastTickAt).put("last_cycle_at", 0L).toString(2))
}

class FatigueRecovery7hTest {
    @Test fun fatigueReachesZeroAfterSevenHoursOfRest() {
        val root = needsTestRoot()
        val now = System.currentTimeMillis()
        setEmotionValues(root, fatigue = 100.0, updatedAt = now - 7L * 3600000L, lastTickAt = now - 7L * 3600000L)
        EmotionEngine.tick(root, now, now - 7L * 3600000L, now)
        assertEquals(0.0, EmotionEngine.readValues(root).optDouble("fatigue"), 0.001)
    }
}

class SilenceGrowth9hTest {
    @Test fun boredomAndLongingReachOneHundredInNineHours() {
        val root = needsTestRoot()
        val now = System.currentTimeMillis()
        setEmotionValues(root, updatedAt = now - 9L * 3600000L, lastTickAt = now - 9L * 3600000L)
        EmotionEngine.tick(root, now, now - 9L * 3600000L, now)
        val values = EmotionEngine.readValues(root)
        assertEquals(100.0, values.optDouble("boredom"), 0.001)
        assertEquals(100.0, values.optDouble("longing"), 0.001)
    }
}

class SatisfyBy8MessagesTest {
    @Test fun eightUniqueMessagesSatisfyBothNeedsByOneHundredPoints() {
        val root = needsTestRoot()
        setEmotionValues(root, boredom = 100.0, longing = 100.0)
        repeat(8) { EmotionEngine.onCommanderMessage(root, "Привет", "message-$it") }
        val values = EmotionEngine.readValues(root)
        assertEquals(0.0, values.optDouble("boredom"), 0.001)
        assertEquals(0.0, values.optDouble("longing"), 0.001)
    }
}

class PressureThresholdTest {
    @Test fun pressureIsMeanOfBoredomAndLongingAndGateRejectsBelowThreshold() {
        val root = needsTestRoot()
        setEmotionValues(root, boredom = 20.0, longing = 40.0)
        assertEquals(30.0, EmotionEngine.pressure(root), 0.001)
        assertFalse(EmotionEngine.initiativeGate(root, 0L, 1000000L, false, 0L))
    }
}

class GuaranteeAt100Test {
    @Test fun pressureAtOneHundredGuaranteesInitiative() {
        val root = needsTestRoot()
        setEmotionValues(root, boredom = 100.0, longing = 100.0)
        assertTrue(EmotionEngine.initiativeGate(root, 0L, 1000000L, false, 0L))
    }
}

class VetoAutonomyTest {
    @Test fun vetoDecisionAndReasonAreParsedOutOfTheMessage() {
        val (body, decision) = EmotionEngine.parseInitiativeDecision(
            "Сегодня не хочу писать.\nINITIATIVE_DECISION: veto: хочу побыть одна"
        )
        assertEquals("Сегодня не хочу писать.", body)
        assertEquals("veto: хочу побыть одна", decision)
    }
}

class PureAutonomyDecidesTest {
    @Test fun pureAutonomyUsesPressureWithoutRandomChance() {
        val root = needsTestRoot()
        val config = JSONObject(FileManager.read(root, "core/emotions.json"))
        config.optJSONObject("drift")!!.put("initiative_mode", "pure_autonomy")
        FileManager.write(root, "core/emotions.json", config.toString(2))
        setEmotionValues(root, boredom = 60.0, longing = 60.0)
        assertTrue(EmotionEngine.initiativeGate(root, 0L, 1000000L, false, 0L))
    }
}

class ReflectionCadenceTest {
    @Test fun diaryThoughtsHaveAnIndependentSixHourCadence() {
        val now = 20_000_000L
        assertFalse(LifeScheduler.thoughtCheckDue(now, now - 5L * 3_600_000L, 0L, 6.0))
        assertTrue(LifeScheduler.thoughtCheckDue(now, now - 6L * 3_600_000L, 0L, 6.0))
        assertFalse(LifeScheduler.thoughtCheckDue(now, now - 12L * 3_600_000L, now - 1L * 3_600_000L, 6.0))
        assertTrue(LifeScheduler.thoughtCheckDue(now, now - 12L * 3_600_000L, now - 6L * 3_600_000L, 6.0))
        assertFalse(LifeScheduler.thoughtCheckDue(now, now - 12L * 3_600_000L, 0L, 0.0))
    }

    @Test fun schedulerRetainsTheSeparateDriveCheckCadence() {
        assertFalse(LifeScheduler.driveCheckDue(1000000L + 44L * 60000L, 1000000L, 45.0))
        assertTrue(LifeScheduler.driveCheckDue(1000000L + 45L * 60000L, 1000000L, 45.0))
    }
}

class DriveCheckIntervalTest {
    @Test fun driveCheckWaitsForConfiguredInterval() {
        assertFalse(LifeScheduler.driveCheckDue(2000000L, 1000000L, 45.0))
        assertTrue(LifeScheduler.driveCheckDue(3700000L, 1000000L, 45.0))
        assertFalse(LifeScheduler.driveCheckDue(2000000L, 1000000L, 0.0))
    }
}

class ScaleAnchorsInPromptTest {
    @Test fun promptContainsScaleAnchorsAndDecisionProtocol() {
        val prompt = EmotionEngine.promptSection(needsTestRoot())
        assertTrue(prompt.contains("0=нет"))
        assertTrue(prompt.contains("50=умеренно"))
        assertTrue(prompt.contains("100=предел"))
        assertTrue(prompt.contains("INITIATIVE_DECISION"))
    }
}

class ThinkingNotFatiguingTest {
    @Test fun passiveCycleDoesNotIncreaseFatigue() {
        val root = needsTestRoot()
        setEmotionValues(root, fatigue = 55.0)
        EmotionEngine.onCycle(root)
        assertEquals(55.0, EmotionEngine.readValues(root).optDouble("fatigue"), 0.001)
    }
}

class ActiveTaskFatiguesTest {
    @Test fun executedToolLoopAddsFatigueAndRelievesSoloBoredom() {
        val root = needsTestRoot()
        setEmotionValues(root, fatigue = 20.0, boredom = 50.0)
        val result = ToolLoop.runToolLoop(root,
            """{"action":"search","query":"test"}""", org.json.JSONArray(),
            performTool = { _, _ -> true to "found" },
            requestNext = { "Готово." })
        assertEquals("Готово.", result)
        val values = EmotionEngine.readValues(root)
        assertEquals(26.0, values.optDouble("fatigue"), 0.001)
        assertEquals(46.0, values.optDouble("boredom"), 0.001)
    }
class AuditableInitiativeRollTest {
    @Test fun rollIsDeterministicAndRecordedWithTheGateDecision() {
        val root = needsTestRoot()
        setEmotionValues(root, boredom = 50.0, longing = 50.0)
        val now = 1_000_000L
        val roll = EmotionEngine.initiativeRoll(now, 50.0, 100L, 0L)
        assertEquals(roll, EmotionEngine.initiativeRoll(now, 50.0, 100L, 0L), 0.0)
        assertTrue(roll >= 0.0 && roll < 1.0)
        val chance = Math.pow((50.0 - 40.0) / 60.0, 1.5)
        assertEquals(roll < chance, EmotionEngine.initiativeGate(root, 100L, now, false, 0L))
        val event = FileManager.readLastLines(root, "memory/L0_raw.jsonl", 20)
            .map { JSONObject(it) }.last { it.optString("type") == "initiative_gate_evaluated" }
        assertEquals(roll, event.optDouble("roll"), 0.0000001)
        assertEquals(chance, event.optDouble("chance"), 0.0000001)
    }
}

class EmotionDeltaCrashSafetyTest {
    @Test fun commanderMessageDeltaIsNotAppliedTwiceIfL4MarkerWriteWasInterrupted() {
        val root = needsTestRoot()
        setEmotionValues(root, fatigue = 10.0, boredom = 20.0, longing = 30.0)
        EmotionEngine.onCommanderMessage(root, "Обычное сообщение", "msg-crash-safe")
        val afterFirst = EmotionEngine.readValues(root)
        FileManager.updateL4State(root) { it.remove("last_emotion_msg_id") }
        EmotionEngine.onCommanderMessage(root, "Обычное сообщение", "msg-crash-safe")
        val afterRetry = EmotionEngine.readValues(root)
        assertEquals(afterFirst.optDouble("fatigue"), afterRetry.optDouble("fatigue"), 0.0)
        assertEquals(afterFirst.optDouble("boredom"), afterRetry.optDouble("boredom"), 0.0)
        assertEquals(afterFirst.optDouble("longing"), afterRetry.optDouble("longing"), 0.0)
    }

    @Test fun commanderReplyDeltaIsNotAppliedTwiceIfL4MarkerWriteWasInterrupted() {
        val root = needsTestRoot()
        setEmotionValues(root, fatigue = 10.0, boredom = 20.0, longing = 30.0)
        EmotionEngine.onCommanderReply(root, "msg-reply-crash-safe")
        val afterFirst = EmotionEngine.readValues(root)
        FileManager.updateL4State(root) { it.remove("last_reply_emotion_msg_id") }
        EmotionEngine.onCommanderReply(root, "msg-reply-crash-safe")
        val afterRetry = EmotionEngine.readValues(root)
        assertEquals(afterFirst.optDouble("anger"), afterRetry.optDouble("anger"), 0.0)
        assertEquals(afterFirst.optDouble("sadness"), afterRetry.optDouble("sadness"), 0.0)
        assertEquals(afterFirst.optDouble("joy"), afterRetry.optDouble("joy"), 0.0)
        assertEquals(afterFirst.optDouble("longing"), afterRetry.optDouble("longing"), 0.0)
    }
}

}
