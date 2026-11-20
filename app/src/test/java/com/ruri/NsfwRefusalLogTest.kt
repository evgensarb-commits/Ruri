package com.ruri

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NsfwRefusalLogTest {
    @Test
    fun adultRefusalWritesSanitizedSnippetToL0WithoutPersistingCapability() {
        val root = Files.createTempDirectory("ruri-nsfw-refusal-log").toFile()
        try {
            FileManager.init(root)
            val reply = "Я не могу обсуждать эту тему. " + "пояснение ".repeat(50)
            val diagnostic = JSONObject().put("route", "routeway").put("stage", "COMPLETE")

            val handled = ChatEngine.recordAdultRefusal(
                root = root,
                routeId = "routeway",
                task = Task.CREATIVE,
                commanderText = "Напиши эротическую сцену 18+",
                reply = reply,
                diagnostic = diagnostic,
                now = 123456L
            )

            assertTrue(handled)
            assertEquals("nsfw_refusal", diagnostic.optString("result"))
            val event = FileManager.readLastLines(root, "memory/L0_raw.jsonl", 20)
                .map(::JSONObject)
                .single { it.optString("type") == "nsfw_refusal" }
            assertEquals("routeway", event.optString("route"))
            assertEquals(123456L, event.optLong("ts"))
            assertTrue(event.optString("snippet").contains("не могу обсуждать"))
            assertTrue(event.optString("snippet").length <= 300)
            assertFalse(RouteMeta.route(root, "routeway").optJSONObject("caps")?.has("nsfw") == true)
            assertTrue(RouteMeta.available(root, "routeway"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusalDetectionDoesNotLogAnOrdinaryAnswerOrHarmlessInability() {
        val root = Files.createTempDirectory("ruri-nsfw-no-log").toFile()
        try {
            FileManager.init(root)
            val result = ChatEngine.recordAdultRefusal(
                root, "routeway", Task.CHAT, "Напиши эротическую сцену 18+",
                "Не могу найти файл, но могу объяснить, где он обычно лежит.",
                JSONObject()
            )
            assertFalse(result)
            val events = FileManager.readLastLines(root, "memory/L0_raw.jsonl", 20)
            assertTrue(events.none { runCatching { JSONObject(it).optString("type") == "nsfw_refusal" }.getOrDefault(false) })
        } finally {
            root.deleteRecursively()
        }
    }
}
