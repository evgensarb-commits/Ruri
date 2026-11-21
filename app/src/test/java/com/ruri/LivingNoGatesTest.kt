package com.ruri

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivingNoGatesTest {
    private fun root() = Files.createTempDirectory("ruri-living-no-gates").toFile().also { FileManager.init(it) }
    private fun models(vararg ids: String) = JSONArray().also { arr ->
        ids.forEachIndexed { i, id -> arr.put(JSONObject().put("id", id).put("name", id)
            .put("api_key", "test-key").put("free_tier", true).put("enabled", true).put("priority", i)) }
    }

    @Test fun MixedProseJsonStrippedTest() {
        val (clean, _, tools) = ToolLoop.stripActions("Вот ответ. {\"action\":\"search\",\"query\":\"кот\"} Продолжение.")
        assertTrue(clean.contains("Вот ответ.")); assertTrue(clean.contains("Продолжение."))
        assertEquals(1, tools.size)
    }

    @Test fun WriteDiaryRoutedToDiaryTest() {
        val (_, diary, tools) = ToolLoop.stripActions("Мысль {\"action\":\"write_diary\",\"text\":\"Мне спокойно\"}")
        assertEquals(listOf("Мне спокойно"), diary); assertTrue(tools.isEmpty())
    }

    @Test fun DiaryMirrorTest() {
        val root = root()
        ChatEngine.appendFinalReply(root, "Сказанная фраза", null, null, "route-a")
        val entries = JSONObject(FileManager.read(root, "memory/L2_diary.json")).getJSONArray("entries")
        assertEquals("said", entries.getJSONObject(entries.length() - 1).optString("kind"))
    }

    @Test fun DiaryKindDedupTest() {
        val root = root()
        Diary.append(root, "Одинаковая фраза", "test", null, "thought")
        Diary.append(root, "Одинаковая фраза", "test", null, "said")
        Diary.append(root, "Одинаковая фраза", "test", null, "said")
        val entries = JSONObject(FileManager.read(root, "memory/L2_diary.json")).getJSONArray("entries")
        assertEquals(2, entries.length())
    }

    @Test fun DiaryDedupStrongTest() {
        val root = root()
        Diary.append(root, "Мы сегодня долго обсуждали проект и решили продолжить завтра", "test", null)
        Diary.append(root, "Мы сегодня долго обсуждали проект и решили продолжить завтра!", "test", null)
        val entries = JSONObject(FileManager.read(root, "memory/L2_diary.json")).getJSONArray("entries")
        assertEquals(1, entries.length())
    }

    @Test fun AlwaysAttemptDespiteStaleCacheTest() {
        val root = root(); RouteMeta.recordFailure(root, "a", "network")
        val ordered = RouteSelector.orderedIndices(root, models("a", "b"), 0, Task.CHAT, online = true, vpn = false)
        assertTrue(ordered.contains(0)); assertTrue(ordered.contains(1))
    }

    @Test fun FailoverAfterExclusiveTest() {
        val ordered = FreeModelRouter.orderedIndices(models("first", "backup"), 0)
        assertEquals(listOf(0, 1), ordered)
    }

    @Test fun PrimaryRouteFirstThenFallbackByScoreTest() {
        val root = root(); repeat(12) { RouteMeta.recordSuccess(root, "backup", Task.CHAT) }
        val ordered = RouteSelector.orderedIndices(root, models("primary", "backup"), 0, Task.CHAT, online = true, vpn = false)
        assertEquals(0, ordered.first())
    }

    @Test fun NoBatteryGateTest() {
        val root = root()
        assertEquals(listOf(0), RouteSelector.orderedIndices(root, models("free"), 0, Task.CHAT, online = true, vpn = false))
    }

    @Test fun NoQuotaBlockTest() {
        val root = root()
        repeat(250) { RouteMeta.recordAttempt(root, "route") }
        assertTrue(RouteMeta.quotaLeft(root, "route", 200))
        assertTrue(RouteMeta.available(root, "route"))
    }

    @Test fun OnlineAfterOfflineReplyTest() {
        val root = root()
        OfflineQueue.markPending(root)
        assertTrue(OfflineQueue.hasPending(root))
        OfflineQueue.clear(root)
        assertFalse(OfflineQueue.hasPending(root))
    }

    @Test fun RouteTagOnMessageTest() {
        val root = root()
        ChatEngine.appendFinalReply(root, "Ответ", null, null, "provider-id")
        val event = FileManager.readLastLines(root, "memory/L0_raw.jsonl", 10).map(::JSONObject)
            .first { it.optString("type") == "message" }
        assertEquals("provider-id", event.optString("route"))
    }

    @Test fun TokenAccountingNoBlockTest() {
        val root = root()
        RouteMeta.addTokens(root, "route", 1234)
        assertEquals(1234L, RouteMeta.route(root, "route").optLong("tokens_today"))
        assertTrue(RouteMeta.quotaLeft(root, "route", 1))
        assertTrue(RouteMeta.available(root, "route"))
    }

    @Test fun RateVsQuotaTest() {
        assertEquals("rate_limit", RouteSelector.classify(429, null, "rate limit exceeded, retry later"))
        assertEquals("quota", RouteSelector.classify(429, null, "daily token quota exhausted"))
        assertEquals("rate_limit", RouteSelector.classify(429, null, "too many requests"))
    }

    @Test fun DesirePromptPreservesFlirtDeltaGuidanceTest() {
        val root = root()
        assertTrue(EmotionEngine.promptSection(root).contains("до +20 уместно"))
    }

    @Test fun DesireTriggersTest() {
        val root = root()
        FileManager.appendEvent(root, JSONObject().put("type", "message").put("who", "commander")
            .put("text", "целую тебя").put("ts", System.currentTimeMillis()))
        val before = EmotionEngine.readValues(root).optDouble("desire")
        EmotionEngine.onCommanderMessage(root, "целую тебя")
        assertEquals(before + 20.0, EmotionEngine.readValues(root).optDouble("desire"), 0.001)
    }
}
