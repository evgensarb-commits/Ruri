package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WriteDiaryUnwrapReturnsTextTest {
    @Test fun writeDiaryActionUnwrapsToTextWithoutRetryOrRawJson() {
        val root = Files.createTempDirectory("ruri-invalid-action").toFile()
        val messages = JSONArray()
        var requested = false
        val result = ToolLoop.unwrapDiaryReply(root,
            """{"action":"write_diary","text":"Сегодня я многое поняла."}""",
            messages) { requested = true; "проза" }
        assertEquals("Сегодня я многое поняла.", result)
        assertFalse(result.startsWith("{"))
        assertTrue(ToolLoop.proseCandidate(result) != null)
        assertFalse(requested)
    }
}

class NoRawJsonInDiaryTest {
    @Test fun guardedDiaryValueIsNeverRawActionJson() {
        val root = Files.createTempDirectory("ruri-diary-guard").toFile()
        val result = ToolLoop.unwrapDiaryReply(root,
            """{"action":"invented_action","text":"Я спокойно размышляю."}""",
            JSONArray()) { "обычная проза" }
        assertEquals("Я спокойно размышляю.", result)
        val diary = JSONObject().put("entries", JSONArray().put(JSONObject().put("text", result)))
        FileManager.write(root, "memory/L2_diary.json", diary.toString())
        val saved = JSONObject(FileManager.read(root, "memory/L2_diary.json"))
            .getJSONArray("entries").getJSONObject(0).getString("text")
        assertEquals("Я спокойно размышляю.", saved)
        assertFalse(saved.startsWith("{"))
    }
}

class ThinkerToolLoopTest {
    @Test fun autonomousToolCallIsRecordedAndFollowedByProse() {
        val root = Files.createTempDirectory("ruri-thinker-tools").toFile()
        FileManager.write(root, "shelf/note.txt", "локальная заметка")
        val messages = JSONArray().put(JSONObject().put("role", "system")
            .put("content", "L0 L1 L2 L3 recall"))
        val result = ToolLoop.unwrapDiaryReply(root,
            """{"action":"list_shelf"}""", messages) { "Нашла заметку и обдумала её." }
        assertEquals("Нашла заметку и обдумала её.", result)
        val events = FileManager.readLastLines(root, "memory/L0_raw.jsonl", 20)
        assertTrue(events.any { JSONObject(it).optString("type") == "tool_result" })
    }
}

class RawUrlsInMessageTest {
    @Test fun sourceUrlsAreStoredAsOptionalMessageField() {
        val root = Files.createTempDirectory("ruri-source-urls").toFile()
        val urls = JSONArray().put("https://example.org/source")
        ChatEngine.appendFinalReply(root, "Краткий ответ", "test-route", urls)
        val event = JSONObject(FileManager.readLastLines(root, "memory/L0_raw.jsonl", 1).last())
        assertEquals("https://example.org/source", event.getJSONArray("source_urls").getString(0))
    }
}

class NoUrlHallucinationPromptTest {
    @Test fun bodyPromptSaysInterfaceShowsUrls() {
        val root = Files.createTempDirectory("ruri-url-prompt").toFile()
        assertTrue(Capabilities.bodySection(root).contains("URL источников покажет интерфейс"))
    }
}

class L0FilterTest {
    @Test fun readLastEventsFiltersTypeAndBoundsCount() {
        val root = Files.createTempDirectory("ruri-l0-filter").toFile()
        listOf("message", "tool_call", "tool_result").forEach { type ->
            FileManager.appendEvent(root, JSONObject().put("type", type).put("text", "тест"))
        }
        val filtered = FileManager.readLastEvents(root, "tool_result", 100)
        assertEquals(1, filtered.length())
        assertEquals("tool_result", filtered.getJSONObject(0).getString("type"))
        assertTrue(FileManager.readLastEvents(root, "all", 1).length() <= 1)
    }
}

class StartupRefreshThrottleTest {
    @Test fun startupRefreshIsLimitedToFiveMinutes() {
        val root = Files.createTempDirectory("ruri-startup-throttle").toFile()
        assertTrue(Probe.claimStartupRefresh(root, 1_000_000L))
        assertFalse(Probe.claimStartupRefresh(root, 1_000_001L))
        assertTrue(Probe.claimStartupRefresh(root, 1_300_000L))
    }
}
