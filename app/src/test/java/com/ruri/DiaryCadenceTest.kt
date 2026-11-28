package com.ruri

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryCadenceTest {
    private val defaults = JSONObject()

    @Test
    fun ordinaryThoughtsCannotBeWrittenEveryTwoMinutes() {
        val now = 10_000_000L
        val last = now - 2 * 60_000L
        assertFalse(DiaryCadence.allowsAt(now, last, 0.0, defaults))
        assertTrue(DiaryCadence.allowsAt(now, now - 16 * 60_000L, 0.0, defaults))
    }

    @Test
    fun highFatigueStretchesTheDiaryPause() {
        val now = 10_000_000L
        assertFalse(DiaryCadence.allowsAt(now, now - 30 * 60_000L, 80.0, defaults))
        assertTrue(DiaryCadence.allowsAt(now, now - 80 * 60_000L, 80.0, defaults))
    }

    @Test
    fun criticalFatigueStopsDiaryWritingEvenAfterLongPause() {
        val now = 10_000_000L
        assertFalse(DiaryCadence.allowsAt(now, now - 4 * 60 * 60_000L, 100.0, defaults))
    }

    @Test
    fun realNotificationCanBypassCriticalFatiguePause() {
        val now = 10_000_000L
        assertFalse(DiaryCadence.allowsAt(now, now - 4 * 60 * 60_000L, 100.0, defaults))
        assertTrue(DiaryCadence.allowsAt(now, now - 2 * 60_000L, 100.0, defaults, bypass = true))
    }

    @Test
    fun cadenceCanBeAdjustedThroughEmotionDriftConfig() {
        val now = 10_000_000L
        val config = JSONObject()
            .put("diary_min_gap_min", 5)
            .put("diary_max_gap_min", 20)
            .put("diary_pause_fatigue_at", 99)
        assertTrue(DiaryCadence.allowsAt(now, now - 6 * 60_000L, 0.0, config))
        assertFalse(DiaryCadence.allowsAt(now, now - 6 * 60_000L, 100.0, config))
    }
}
