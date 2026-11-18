package com.ruri

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
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
