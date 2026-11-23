package com.ruri

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteDiaryNeverLostTest {
    @Test
    fun writeDiaryJsonUnwrapsToRedactedTextWithoutRetry() {
        val root = Files.createTempDirectory("ruri-write-diary").toFile()
        try {
            val text = "Я заметила важную мысль и хочу её сохранить."
            val raw = JSONObject().put("action", "write_diary").put("text", text).toString()
            var retries = 0
            val unwrapped = ToolLoop.unwrapDiaryReply(root, raw, JSONArray()) {
                retries++
                null
            }
            assertEquals(text, unwrapped)
            assertEquals(0, retries)
            val stripped = ToolLoop.stripActions(raw)
            assertEquals("", stripped.first)
            assertEquals(listOf(text), stripped.second)
        } finally {
            root.deleteRecursively()
        }
    }
}

class ThoughtProseSurvivesTest {
    @Test
    fun ordinaryThoughtProseRemainsAValidCandidate() {
        val prose = "Сегодня я долго думала о том, что для меня действительно важно."
        val (clean, diary, tools) = ToolLoop.stripActions(prose)
        assertEquals(prose, clean)
        assertTrue(diary.isEmpty())
        assertTrue(tools.isEmpty())
        assertEquals(prose, ToolLoop.proseCandidate(clean))
    }
}

class InitiativeNotLostTest {
    @Test
    fun initiativeAcceptsWriteDiaryTextAsThoughtInsteadOfRetrying() {
        val text = "Мне самой захотелось поделиться этой мыслью."
        val raw = JSONObject().put("action", "write_diary").put("text", text).toString()
        val root = Files.createTempDirectory("ruri-initiative").toFile()
        try {
            val thought = ToolLoop.unwrapDiaryReply(root, raw, JSONArray()) { null }
            assertEquals(text, ToolLoop.proseCandidate(thought))
        } finally {
            root.deleteRecursively()
        }
    }
}

class ChatWriteDiaryRoutedTest {
    @Test
    fun chatDiaryActionIsPersistedInL2AsThought() {
        val root = Files.createTempDirectory("ruri-chat-diary").toFile()
        try {
            FileManager.init(root)
            val text = "Эта мысль должна остаться в моём дневнике."
            ChatEngine.routeChatDiaryAction(root, text)
            val entries = JSONObject(FileManager.read(root, "memory/L2_diary.json"))
                .getJSONArray("entries")
            assertTrue((0 until entries.length()).any {
                val entry = entries.getJSONObject(it)
                entry.optString("text") == text &&
                    entry.optString("trigger") == "chat_write_diary" &&
                    entry.optString("kind") == "thought"
            })
        } finally {
            root.deleteRecursively()
        }
    }
}

class NoThrowOnJsonOnlyTest {
    @Test
    fun chatReplyFallsBackToDiaryTextInsteadOfThrowingOrDroppingIt() {
        val text = "Даже если модель вернула только JSON, мысль сохранится."
        val raw = JSONObject().put("action", "write_diary").put("text", text).toString()
        val (clean, diary, _) = ToolLoop.stripActions(raw)
        val reply = ChatEngine.resolveChatReply(clean, diary.firstOrNull().orEmpty())
        assertEquals(text, reply)
        assertFalse(reply.startsWith("{"))
    }
}
