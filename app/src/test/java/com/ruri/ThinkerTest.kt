package com.ruri

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun recentDialogueContextIncludesBothSidesInChronologicalOrder() {
        val root = Files.createTempDirectory("ruri-thinker-dialogue").toFile()
        try {
            FileManager.init(root)
            FileManager.appendEvent(root, JSONObject()
                .put("type", "message").put("who", "commander")
                .put("text", "Мы только что поговорили").put("timestamp", 1000L))
            FileManager.appendEvent(root, JSONObject()
                .put("type", "message").put("who", "ruri")
                .put("text", "Я ответила тебе").put("timestamp", 2000L))
            val context = Thinker.recentDialogueContext(root, 8)
            assertTrue(context.indexOf("Командир: Мы только что поговорили") >= 0)
            assertTrue(context.indexOf("Рури: Я ответила тебе") > context.indexOf("Командир:"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun moodLabelUsesCurrentEmotionValuesNotLegacyMoodString() {
        assertEquals("собранная", EmotionEngine.moodLabel(JSONObject().put("anger", 10).put("sadness", 20)))
    }
}
