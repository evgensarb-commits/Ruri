package com.ruri

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkerTest {
    @Test
    fun moodComesFromEmotionChannelsInsteadOfLegacyIgnoredCount() {
        assertEquals("обиженная", EmotionEngine.moodLabel(JSONObject().put("hurt", 65)))
    }

    @Test
    fun ignoredEventsPersistHurtAndEventuallyProduceOffendedMood() {
        val root = Files.createTempDirectory("ruri-thinker-emotions").toFile()
        try {
            FileManager.init(root)
            repeat(4) { EmotionEngine.onIgnored(root) }
            assertEquals(70.0, EmotionEngine.readValues(root).getDouble("hurt"), 0.0)
            assertEquals("обиженная", EmotionEngine.moodLabel(EmotionEngine.readValues(root)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun moodLabelUsesCurrentEmotionValuesNotLegacyMoodString() {
        assertEquals("собранная", EmotionEngine.moodLabel(JSONObject().put("hurt", 0)))
    }
}
