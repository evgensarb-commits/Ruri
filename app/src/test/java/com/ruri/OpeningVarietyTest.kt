package com.ruri

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

private fun styleGuardRoot(): File = Files.createTempDirectory("ruri-style-guard").toFile()

class OpeningVarietyNoteTest {
    @Test fun threeIdenticalLeadingGesturesProduceAdaptiveFeedback() {
        val root = styleGuardRoot()
        try {
            FileManager.init(root)
            repeat(3) { index ->
                FileManager.appendEvent(root, JSONObject()
                    .put("type", "message")
                    .put("who", "ruri")
                    .put("text", "*поправляет волосы* Ну что, дальше? $index"))
            }
            val note = StyleGuard.repetitiveOpeningNote(root)
            assertTrue(note.contains("одного жеста"))
            assertTrue(note.contains("поправляет волосы"))
            assertTrue(note.contains("Варьируй вход"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class HistoryStageStripTest {
    @Test fun oldAssistantGesturesAreStrippedButLastTwoMessagesStayVerbatim() {
        val old = "*отводит взгляд* Это старая реплика."
        assertEquals("Это старая реплика.", StyleGuard.stripOlderOpening("ruri", 0, 5, old))
        assertEquals(old, StyleGuard.stripOlderOpening("ruri", 3, 5, old))
        assertEquals(old, StyleGuard.stripOlderOpening("ruri", 4, 5, old))
        assertEquals(old, StyleGuard.stripOlderOpening("commander", 0, 5, old))
    }
}

class PenaltiesSeedPresentTest {
    @Test fun chatAndThinkerRequestsUsePenaltyFieldsAndVariableSeed() {
        val candidates = listOf(
            File("app/src/main/java/com/ruri/ChatEngine.kt"),
            File("src/main/java/com/ruri/ChatEngine.kt")
        )
        val chat = candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("ChatEngine source not found")
        val thinkerCandidates = listOf(
            File("app/src/main/java/com/ruri/Thinker.kt"),
            File("src/main/java/com/ruri/Thinker.kt")
        )
        val thinker = thinkerCandidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("Thinker source not found")
        listOf(chat, thinker).forEach { source ->
            assertTrue(source.contains("presence_penalty"))
            assertTrue(source.contains("frequency_penalty"))
            assertTrue(source.contains("System.currentTimeMillis() % 100000L"))
        }
        assertTrue(chat.contains(".put(\"temperature\", 0.9)"))
    }
}

class CharacterVoiceV2Test {
    @Test fun oneTimeMigrationRewritesVoiceWithoutReplacingIdentityCore() {
        val root = styleGuardRoot()
        try {
            FileManager.init(root)
            val character = JSONObject(FileManager.read(root, "core/character.json"))
                .put("name", "Рури-тест")
                .put("designation", "RU-R1")
                .put("persona", "Моя сохранённая дерзкая персона.")
                .put("speech_rules", "Начинай каждый ответ с действия.")
                .put("character_voice_v2", false)
            FileManager.write(root, "core/character.json", character.toString(2))
            FileManager.init(root)
            val migrated = JSONObject(FileManager.read(root, "core/character.json"))
            val speech = migrated.optString("speech_rules")
            assertEquals("Рури-тест", migrated.optString("name"))
            assertEquals("RU-R1", migrated.optString("designation"))
            assertEquals("Моя сохранённая дерзкая персона.", migrated.optString("persona"))
            assertTrue(migrated.optBoolean("character_voice_v2"))
            assertTrue(speech.contains("варьируй вход"))
            assertFalse(speech.contains("начинай каждый ответ с действия"))
            assertTrue(migrated.optJSONArray("opening_exemplars")!!.length() >= 3)
            assertTrue(migrated.optJSONArray("regulators")!!.toString().contains("тон и выбор слов"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class EmotionToneNotChoreographyTest {
    @Test fun emotionPromptExplicitlyControlsToneNotOpeningGestures() {
        val root = styleGuardRoot()
        try {
            FileManager.init(root)
            val prompt = EmotionEngine.promptSection(root)
            assertTrue(prompt.contains("влияют на тон и выбор слов"))
            assertTrue(prompt.contains("НЕ требуют обязательной ремарки-жеста"))
        } finally {
            root.deleteRecursively()
        }
    }
}
