package com.ruri

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkerTest {
    @Test
    fun twoOrMoreIgnoredInitiativesRestoreOffendedMood() {
        val state = JSONObject().put("ignored_count", 2).put("bond", 0.9)
        assertEquals("обиженная", Thinker.moodFor(state, "maintenance"))
    }

    @Test
    fun ignoredEventAlsoSetsOffendedMood() {
        val state = JSONObject().put("ignored_count", 1)
        assertEquals("обиженная", Thinker.moodFor(state, "initiative_ignored"))
    }

    @Test
    fun alreadyOffendedMoodSurvivesMaintenanceWake() {
        val state = JSONObject().put("mood", "обиженная").put("ignored_count", 0).put("bond", 0.9)
        assertEquals("обиженная", Thinker.moodFor(state, "maintenance"))
    }
}
