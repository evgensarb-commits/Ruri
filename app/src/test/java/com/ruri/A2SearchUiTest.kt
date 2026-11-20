package com.ruri

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHonestyTest {
    @Test fun errorAndEmptySearchResultsAreFalseAndModelGetsHonestyNote() {
        val root = Files.createTempDirectory("ruri-search-honesty").toFile()
        try {
            FileManager.init(root)
            val failingResponses = listOf(
                """{"error":"captcha"}""",
                """{"query":"test","results":[]}"""
            )
            for (raw in failingResponses) {
                val messages = JSONArray()
                val meta = JSONObject()
                var sawHonestyNote = false
                val finalReply = ToolLoop.runToolLoop(
                    root,
                    """{"action":"search","query":"test"}""",
                    messages,
                    meta,
                    performTool = { _, _ -> ChatEngine.isSuccessfulSearchResult(raw) to raw }
                ) { next ->
                    sawHonestyNote = (0 until next.length()).any { i ->
                        val item = next.optJSONObject(i) ?: JSONObject()
                        item.optString("role") == "system" &&
                            item.optString("content").contains("НЕ утверждай")
                    }
                    "Поиск не удался."
                }
                assertFalse(ChatEngine.isSuccessfulSearchResult(raw))
                assertFalse(meta.optBoolean("search_ok", true))
                assertEquals(0, meta.optJSONArray("source_urls")?.length() ?: 0)
                assertTrue(sawHonestyNote)
                assertEquals("Поиск не удался.", finalReply)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun nonEmptyValidResultsAreSuccessful() {
        assertTrue(ChatEngine.isSuccessfulSearchResult(
            """{"query":"test","results":[{"title":"Example","url":"https://example.org"}]}"""
        ))
    }
}

class CseConfigTest {
    @Test fun cseCredentialsCanBeSavedAndLoadedFromSearchJson() {
        val root = Files.createTempDirectory("ruri-cse-config").toFile()
        try {
            FileManager.init(root)
            val config = JSONObject()
                .put("cse_key", "test-key")
                .put("cse_cx", "test-search-engine")
            FileManager.write(root, "core/search.json", config.toString(2))

            val loaded = JSONObject(FileManager.read(root, "core/search.json"))
            assertEquals("test-key", loaded.getString("cse_key"))
            assertEquals("test-search-engine", loaded.getString("cse_cx"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class SourceUrlsTest {
    @Test fun chatMessagePersistsSearchStatusAndSourceUrls() {
        val root = Files.createTempDirectory("ruri-source-meta").toFile()
        try {
            FileManager.init(root)
            val meta = JSONObject()
                .put("search_ok", true)
                .put("source_urls", JSONArray()
                    .put("https://example.org/one")
                    .put("https://example.org/two"))
            ChatEngine.appendFinalReply(root, "Нашла источники.", null, meta)

            val event = FileManager.readLastLines(root, "memory/L0_raw.jsonl", 10)
                .map { JSONObject(it) }
                .last { it.optString("type") == "message" && it.optString("who") == "ruri" }
            assertTrue(event.optBoolean("search_ok"))
            assertEquals(2, event.getJSONArray("source_urls").length())
            assertEquals("https://example.org/one", event.getJSONArray("source_urls").getString(0))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun unsuccessfulSearchStatusIsPersistedEvenWithoutSourceUrls() {
        val root = Files.createTempDirectory("ruri-source-meta-failed").toFile()
        try {
            FileManager.init(root)
            val meta = JSONObject().put("search_ok", false).put("source_urls", JSONArray())
            ChatEngine.appendFinalReply(root, "Поиск не удался.", null, meta)
            val event = FileManager.readLastLines(root, "memory/L0_raw.jsonl", 10)
                .map { JSONObject(it) }
                .last { it.optString("type") == "message" && it.optString("who") == "ruri" }
            assertFalse(event.optBoolean("search_ok", true))
            assertEquals(0, event.getJSONArray("source_urls").length())
        } finally {
            root.deleteRecursively()
        }
    }
}
