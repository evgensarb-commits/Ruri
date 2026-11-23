package com.ruri

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkerTest {
    @Test
    fun moodComesFromNewEmotionChannelsInsteadOfLegacyIgnoredCount() {
        assertEquals("обиженная", EmotionEngine.moodLabel(JSONObject().put("anger", 65).put("sadness", 55)))
    }

    @Test
    fun ignoredEventsIncreaseAngerAndSadnessUntilOffendedMood() {
        val root = Files.createTempDirectory("ruri-thinker-emotions").toFile()
        try {
            FileManager.init(root)
            repeat(5) { EmotionEngine.onIgnored(root) }
            val values = EmotionEngine.readValues(root)
            assertEquals(70.0, values.getDouble("anger"), 0.0)
            assertEquals(70.0, values.getDouble("sadness"), 0.0)
            assertEquals("обиженная", EmotionEngine.moodLabel(values))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun moodLabelUsesCurrentEmotionValuesNotLegacyMoodString() {
        assertEquals("собранная", EmotionEngine.moodLabel(JSONObject().put("anger", 10).put("sadness", 20)))
    }
}
