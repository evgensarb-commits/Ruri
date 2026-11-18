package com.ruri

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryRetrieverTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("ru-r1-memory-recall").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun recallsDurableFactsByMeaningfulWords() {
        FileManager.write(
            root,
            "memory/L1_facts.json",
            JSONObject().put(
                "facts",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("text", "Командир предпочитает спокойные прогулки у моря")
                        .put("confidence", 0.96)
                        .put("created_at", System.currentTimeMillis())
                )
            ).toString()
        )

        val result = MemoryRetriever.recall(root, "Что Командир любит делать у моря?")
        assertTrue(result.contains("спокойные прогулки у моря"))
        assertTrue(result.contains("устойчивый факт"))
    }

    @Test
    fun matchesRussianInflectionVariants() {
        FileManager.write(
            root,
            "memory/L1_facts.json",
            JSONObject().put(
                "facts",
                org.json.JSONArray().put(
                    JSONObject().put("text", "Мы однажды обсуждали старый маяк на берегу")
                        .put("confidence", 0.9)
                )
            ).toString()
        )

        val result = MemoryRetriever.recall(root, "Расскажи про маяка")
        assertTrue(result.contains("старый маяк"))
    }


    @Test
    fun followsAssociationsFromDirectMatchToRelatedEpisode() {
        FileManager.write(
            root,
            "memory/L1_facts.json",
            JSONObject().put(
                "facts",
                org.json.JSONArray()
                    .put(JSONObject().put(
                        "text",
                        "Мы обсуждали проект маяка на севере"
                    ).put("confidence", 0.95))
            ).toString()
        )
        FileManager.write(
            root,
            "memory/L2_diary.json",
            JSONObject().put(
                "entries",
                org.json.JSONArray()
                    .put(JSONObject().put(
                        "text",
                        "Вспомнила наш разговор о севере, побережье и старой обсерватории"
                    ).put("ts", System.currentTimeMillis()))
                    .put(JSONObject().put(
                        "text",
                        "Сегодня читала о комнатных растениях и поливе"
                    ).put("ts", System.currentTimeMillis()))
            ).toString()
        )

        val result = MemoryRetriever.recall(root, "Расскажи о проекте", limit = 6)
        assertTrue(result.contains("проект маяка"))
        assertTrue(result.contains("старой обсерватории"))
        assertTrue(result.contains("связано через:"))
        assertTrue(!result.contains("комнатных растениях"))
    }

    @Test
    fun recallsOlderNotificationBodiesByMeaningfulWords() {
        FileManager.appendEvent(
            root,
            JSONObject()
                .put("type", "phone_notification")
                .put("package", "com.example.mail")
                .put("title", "Важное сообщение о маяке")
                .put("text", "Командиру прислали детали проекта старого маяка на побережье")
                .put("timestamp", System.currentTimeMillis())
        )
        FileManager.appendEvent(
            root,
            JSONObject()
                .put("type", "phone_notification")
                .put("package", "com.example.weather")
                .put("title", "Погода на завтра")
                .put("text", "Ожидается дождь и сильный ветер")
                .put("timestamp", System.currentTimeMillis())
        )

        val result = MemoryRetriever.recall(root, "Что было в уведомлении о маяке?")

        assertTrue(result.contains("детали проекта старого маяка"))
        assertTrue(result.contains("уведомление телефона"))
        assertFalse(result.contains("Ожидается дождь"))
    }

    @Test
    fun returnsEmptyWhenQueryHasNoUsefulTerms() {
        assertTrue(MemoryRetriever.recall(root, "это как что для меня").isBlank())
    }

    @Test
    fun recallsOlderConversationEventsOutsideRecentChatWindow() {
        repeat(15) { index ->
            FileManager.appendEvent(
                root,
                JSONObject()
                    .put("type", "message")
                    .put("who", if (index % 2 == 0) "commander" else "ruri")
                    .put("text", if (index == 0) "Мы обсуждали проект старого маяка у моря" else "Обычное сообщение номер $index")
            )
        }

        val result = MemoryRetriever.recall(root, "Что мы обсуждали про маяк?")
        assertTrue(result.contains("проект старого маяка у моря"))
    }
}
