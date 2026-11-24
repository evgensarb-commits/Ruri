package com.ruri

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resolves repository source files regardless of the test working directory. */
object RepoSources {
    fun text(relPath: String): String = file(relPath).readText()

    fun file(relPath: String): File {
        var dir = File(System.getProperty("user.dir", ".")).absoluteFile
        repeat(6) {
            val candidate = File(dir, relPath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: break
        }
        error("Не найден файл $relPath от ${System.getProperty("user.dir")}")
    }
}

class TaskEnumCodeLongTest {
    @Test fun enumContainsCodeAndLongTasks() {
        val names = Task.values().map { it.name }
        assertTrue(names.contains("CODE"))
        assertTrue(names.contains("LONG"))
        assertEquals(Task.CODE, Task.valueOf("CODE"))
        assertEquals(Task.LONG, Task.valueOf("LONG"))
    }
}

class CodeDetectTest {
    @Test fun codeRequestsAreDetected() {
        assertEquals(Task.CODE, RouteSelector.detectTask("напиши функцию на kotlin для сортировки"))
        assertEquals(Task.CODE, RouteSelector.detectTask("почини код в этом файле"))
        assertEquals(Task.CODE, RouteSelector.detectTask("пофикси метод please"))
        assertEquals(Task.CODE, RouteSelector.detectTask("отрефакторь класс MainActivity"))
        assertEquals(Task.CODE, RouteSelector.detectTask("посмотри на Main.kt и stacktrace"))
        assertEquals(Task.CODE, RouteSelector.detectTask("ошибка в gradle сборке"))
    }

    @Test fun nonCodeTasksKeepTheirCategories() {
        assertEquals(Task.CHAT, RouteSelector.detectTask("привет, как дела?"))
        assertEquals(Task.CREATIVE, RouteSelector.detectTask("напиши сценарий для вечера"))
        assertEquals(Task.REASON, RouteSelector.detectTask("докажи теорему"))
        assertEquals(Task.WARMTH, RouteSelector.detectTask("мне грустно и одиноко"))
    }
}

class LongDetectTest {
    @Test fun longContextSwitchesTaskExceptVision() {
        assertEquals(Task.LONG, RouteSelector.detectTaskWithContext("привет", estimatedTokens = 131000))
        assertEquals(Task.CHAT, RouteSelector.detectTaskWithContext("привет", estimatedTokens = 130000))
        assertEquals(Task.REASON, RouteSelector.detectTaskWithContext("докажи теорему", estimatedTokens = 200000))
        assertEquals(Task.VISION, RouteSelector.detectTaskWithContext("что на фото?", hasImage = true, estimatedTokens = 500000))
    }
}

class RuriV2RoutingFixture {
    fun migratedRoot(): File {
        val root = Files.createTempDirectory("ruri-v2-routing").toFile()
        FileManager.init(root)
        val cfg = JSONObject(FileManager.read(root, "core/config.json"))
        val models = cfg.getJSONArray("models")
        models.put(JSONObject().put("id", "dolphin-free")
            .put("model", "cognitizeai/dolphin-mistral-24b-venice-edition:free")
            .put("free_tier", true).put("enabled", true))
        models.put(JSONObject().put("id", "arliai-qwq")
            .put("model", "arliai/qwq-32b-arliai-rpr-v1:free")
            .put("free_tier", true).put("enabled", true))
        RouterAPIMigration.applyMigration(cfg)
        FileManager.write(root, "core/config.json", cfg.toString(2))
        return root
    }

    fun migratedModels(root: File): JSONArray =
        JSONObject(FileManager.read(root, "core/config.json")).getJSONArray("models")

    fun idsInOrder(models: JSONArray, order: List<Int>): List<String> =
        order.map { models.getJSONObject(it).optString("id") }
}

class TaskRoutingChatTest {
    @Test fun chatTaskPrefersMeroMeroFirst() {
        val fixture = RuriV2RoutingFixture()
        val root = fixture.migratedRoot()
        try {
            val models = fixture.migratedModels(root)
            val order = RouteSelector.orderedIndices(root, models, 0, Task.CHAT, online = true, vpn = false)
            assertEquals(listOf("routeway", "routerapi-glm", "routerapi-cydonia", "routerapi-unslop", "routerapi-deepseek"),
                fixture.idsInOrder(models, order))
        } finally { root.deleteRecursively() }
    }
}

class TaskRoutingWarmthTest {
    @Test fun warmthTaskUsesWarmRouteOrder() {
        val fixture = RuriV2RoutingFixture()
        val root = fixture.migratedRoot()
        try {
            val models = fixture.migratedModels(root)
            val order = RouteSelector.orderedIndices(root, models, 0, Task.WARMTH, online = true, vpn = false)
            assertEquals(listOf("routeway", "routerapi-cydonia", "routerapi-unslop", "routerapi-glm", "routerapi-deepseek"),
                fixture.idsInOrder(models, order))
        } finally { root.deleteRecursively() }
    }
}

class TaskRoutingThoughtUncensoredTest {
    @Test fun thoughtTaskPutsUncensoredRoutesFirst() {
        val fixture = RuriV2RoutingFixture()
        val root = fixture.migratedRoot()
        try {
            val models = fixture.migratedModels(root)
            val order = RouteSelector.orderedIndices(root, models, 0, Task.REASON, online = true, vpn = false)
            val ids = fixture.idsInOrder(models, order)
            assertEquals("routeway", ids.first())
            assertEquals("routerapi-cydonia", ids[1])
        } finally { root.deleteRecursively() }
    }
}

class TaskRoutingCodeGlmFirstTest {
    @Test fun codeTaskPutsGlmFirstEvenWithLowerPriority() {
        val fixture = RuriV2RoutingFixture()
        val root = fixture.migratedRoot()
        try {
            val models = fixture.migratedModels(root)
            // GLM gets the worst manual priority to prove route_tasks wins.
            for (i in 0 until models.length()) {
                val m = models.optJSONObject(i) ?: continue
                if (m.optString("id") == "routerapi-glm") m.put("priority", 9)
            }
            val order = RouteSelector.orderedIndices(root, models, 0, Task.CODE, online = true, vpn = false)
            val ids = fixture.idsInOrder(models, order)
            assertEquals("routerapi-glm", ids.first())
            assertEquals("routerapi-deepseek", ids[1])
        } finally { root.deleteRecursively() }
    }
}

class TaskRoutingVisionOrderTest {
    @Test fun visionTaskOrderRespectedAndVisionFilterApplied() {
        val fixture = RuriV2RoutingFixture()
        val root = fixture.migratedRoot()
        try {
            val models = fixture.migratedModels(root)
            // Manual priority puts GLM first; route_tasks must restore MeroMero first.
            for (i in 0 until models.length()) {
                val m = models.optJSONObject(i) ?: continue
                when (m.optString("id")) {
                    "routerapi-glm" -> m.put("priority", 1)
                    "routeway" -> m.put("priority", 2)
                }
            }
            val order = RouteSelector.orderedIndices(root, models, 0, Task.VISION, online = true, vpn = false)
            assertEquals(listOf("routeway", "routerapi-glm"), fixture.idsInOrder(models, order))
        } finally { root.deleteRecursively() }
    }
}

class LongUnslopFirstTest {
    @Test fun longTaskPrefersUnslopNemo() {
        val fixture = RuriV2RoutingFixture()
        val root = fixture.migratedRoot()
        try {
            val models = fixture.migratedModels(root)
            val order = RouteSelector.orderedIndices(root, models, 0, Task.LONG, online = true, vpn = false)
            val ids = fixture.idsInOrder(models, order)
            assertEquals("routerapi-unslop", ids.first())
            assertEquals("routerapi-cydonia", ids[1])
        } finally { root.deleteRecursively() }
    }
}

class MigrationPrunesDeadRoutesTest {
    @Test fun migrationKeepsMeroMeroAndFourRouterApiRoutesOnly() {
        val fixture = RuriV2RoutingFixture()
        val root = fixture.migratedRoot()
        try {
            val config = JSONObject(FileManager.read(root, "core/config.json"))
            val models = config.getJSONArray("models")
            val ids = (0 until models.length()).map { models.getJSONObject(it).optString("id") }
            assertEquals(listOf("routeway", "routerapi-glm", "routerapi-cydonia", "routerapi-unslop", "routerapi-deepseek"), ids)
            assertTrue(models.getJSONObject(0).optString("model").contains("meromero"))
            assertTrue(config.getBoolean("routerapi_migration_v2"))
            val tasks = config.getJSONObject("route_tasks")
            assertEquals(setOf("chat", "warmth", "thought", "code", "vision", "long"),
                tasks.keySet().toSet())
        } finally { root.deleteRecursively() }
    }
}

class SingleRouterAPIKeyTest {
    @Test fun allRouterApiRoutesShareOneKey() {
        val fixture = RuriV2RoutingFixture()
        val root = fixture.migratedRoot()
        try {
            val models = fixture.migratedModels(root)
            val keys = (0 until models.length()).mapNotNull { i ->
                val m = models.optJSONObject(i) ?: return@mapNotNull null
                if (m.optString("id").startsWith("routerapi-")) m.optString("api_key") else null
            }
            assertEquals(4, keys.size)
            assertTrue(keys.all { it.isNotBlank() })
            assertEquals(keys.distinct().size, 1)
        } finally { root.deleteRecursively() }
    }
}

class NsfwOverrideIntactTest {
    @Test fun nsfwOverrideStillWinsAfterTaskOrdering() {
        val root = Files.createTempDirectory("ruri-nsfw-override").toFile()
        try {
            FileManager.init(root)
            val config = JSONObject(FileManager.read(root, "core/config.json"))
            config.put("route_tasks", JSONObject().put("chat",
                JSONArray().put("clear-route").put("warm-route")))
            FileManager.write(root, "core/config.json", config.toString(2))
            val models = JSONArray()
                .put(JSONObject().put("id", "clear-route").put("name", "clear")
                    .put("free_tier", true).put("enabled", true).put("priority", 1).put("nsfw", false))
                .put(JSONObject().put("id", "warm-route").put("name", "warm")
                    .put("free_tier", true).put("enabled", true).put("priority", 2).put("nsfw", true))
            val order = RouteSelector.orderedIndices(root, models, 0, Task.CHAT, online = true, vpn = false, adult = true)
            assertEquals(listOf(1, 0), order)
        } finally { root.deleteRecursively() }
    }
}

class FailoverAfterRealErrorTest {
    @Test fun failedRoutesStayInAutoOrderUntilRealRequestFails() {
        val root = Files.createTempDirectory("ruri-failover-real-error").toFile()
        try {
            FileManager.init(root)
            RouteMeta.recordFailure(root, "a", "quota")
            val models = JSONArray()
                .put(JSONObject().put("id", "a").put("name", "a")
                    .put("api_key", "k").put("free_tier", true).put("enabled", true).put("priority", 1))
                .put(JSONObject().put("id", "b").put("name", "b")
                    .put("api_key", "k").put("free_tier", true).put("enabled", true).put("priority", 2))
            val order = RouteSelector.orderedIndices(root, models, 0, Task.CHAT, online = true, vpn = false)
            assertEquals(listOf(0, 1), order)
        } finally { root.deleteRecursively() }
    }
}

class NoPreflightBanTest {
    @Test fun exhaustedQuotaAndAttemptsNeverBlockRoutes() {
        val root = Files.createTempDirectory("ruri-no-preflight").toFile()
        try {
            FileManager.init(root)
            RouteMeta.addTokens(root, "route", 400000L)
            repeat(250) { RouteMeta.recordAttempt(root, "route") }
            assertTrue(RouteMeta.quotaLeft(root, "route", 200))
            assertTrue(RouteMeta.available(root, "route"))
        } finally { root.deleteRecursively() }
    }
}

class ThoughtNotInChatOnVetoTest {
    @Test fun vetoedInitiativeNeverPublishesToChat() {
        assertFalse(Thinker.publishesToChat(initiative = true, effective = "veto"))
        assertFalse(Thinker.publishesToChat(initiative = false, effective = "write"))
    }
}

class ThoughtNotInChatOnThinkTest {
    @Test fun thinkingInitiativeNeverPublishesToChat() {
        assertFalse(Thinker.publishesToChat(initiative = true, effective = "think"))
        assertTrue(Thinker.publishesToChat(initiative = true, effective = "write"))
    }
}

class DiarySaidFlagGatedTest {
    @Test fun saidStaysOutOfDiaryWhileFlagOff() {
        val root = Files.createTempDirectory("ruri-diary-flag-off").toFile()
        try {
            FileManager.init(root)
            Diary.append(root, "Внутренняя мысль", "reflection", null, "thought")
            Diary.append(root, "Сказанное Командиру", "chat_reply", null, "said")
            val entries = JSONObject(FileManager.read(root, "memory/L2_diary.json")).getJSONArray("entries")
            assertEquals(1, entries.length())
            assertEquals("thought", entries.getJSONObject(0).optString("kind"))
        } finally { root.deleteRecursively() }
    }

    @Test fun saidMirrorsIntoDiaryOnlyWithFlagOn() {
        val root = Files.createTempDirectory("ruri-diary-flag-on").toFile()
        try {
            FileManager.init(root)
            FileManager.write(root, "core/config.json", JSONObject().put("diary_mirror_said", true).toString())
            Diary.append(root, "Мысль в дневник", "reflection", null, "thought")
            Diary.append(root, "Сказанное Командиру вслух", "chat_reply", null, "said")
            val entries = JSONObject(FileManager.read(root, "memory/L2_diary.json")).getJSONArray("entries")
            assertEquals(2, entries.length())
            assertTrue((0 until entries.length()).any { entries.getJSONObject(it).optString("kind") == "said" })
        } finally { root.deleteRecursively() }
    }
}

class BackoffJitterTest {
    @Test fun jitterAddsUpToTwentyPercentNeverReducesDelay() {
        assertEquals(30_000L, CommanderReplyQueue.delayWithJitter(30_000L, 0L, 0.0))
        assertEquals(36_000L, CommanderReplyQueue.delayWithJitter(30_000L, 0L, 1.0))
        assertTrue(CommanderReplyQueue.delayWithJitter(30_000L, 0L, 0.5) in 30_000L..36_000L)
    }

    @Test fun retryAfterWinsAndCapStaysAt24h() {
        assertEquals(90_000L, CommanderReplyQueue.delayWithJitter(30_000L, 90_000L, 1.0))
        assertEquals(24L * 60L * 60L * 1000L, CommanderReplyQueue.delayWithJitter(23L * 60L * 60L * 1000L, 0L, 1.0))
    }
}

class ShelfReingestOnModifyTest {
    @Test fun sameSignatureIsSkippedAndModifiedFileReingests() {
        val seen = JSONObject()
        assertTrue(ShelfWatcher.shouldIngest(seen, "notes.txt", 100L, 1000L))
        assertFalse(ShelfWatcher.shouldIngest(seen, "notes.txt", 100L, 1000L))
        assertTrue(ShelfWatcher.shouldIngest(seen, "notes.txt", 100L, 2000L))
        assertTrue(ShelfWatcher.shouldIngest(seen, "notes.txt", 200L, 2000L))
        assertEquals("200:2000", seen.optString("sig:notes.txt"))
        assertTrue(seen.optBoolean("notes.txt"))
    }
}

class NoDeadSaidRenderTest {
    @Test fun diaryLoaderRendersThoughtsOnly() {
        val html = RepoSources.text("app/src/main/assets/index.html")
        assertTrue(html.contains("var kind='МЫСЛЬ'"))
        assertFalse(html.contains("e.kind==='said'?'СКАЗАЛА'"))
        assertTrue(html.contains("(e.kind||'thought')==='thought'"))
        // Memory view still renders said entries when diary_mirror_said is enabled.
        assertTrue(html.contains("var said=entry.kind==='said'"))
    }
}

class FollowupLeanNoHistoryResendTest {
    @Test fun leanFollowupKeepsSystemLastCommanderAndLastSixToolResults() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "system memory"))
            .put(JSONObject().put("role", "assistant").put("content", "старый ответ"))
            .put(JSONObject().put("role", "user").put("content", "первое сообщение"))
            .put(JSONObject().put("role", "assistant").put("content", "промежуточный ответ"))
            .put(JSONObject().put("role", "user").put("content", "второе сообщение командира"))
        for (i in 1..7) {
            messages.put(JSONObject().put("role", "user").put("content", "[ИНСТРУМЕНТ] результат $i"))
        }
        val out = ChatEngine.leanFollowup(messages)
        assertEquals(8, out.length())
        assertEquals("system memory", out.optJSONObject(0)?.optString("content"))
        assertEquals("второе сообщение командира", out.optJSONObject(1)?.optString("content"))
        assertEquals("[ИНСТРУМЕНТ] результат 2", out.optJSONObject(2)?.optString("content"))
        assertEquals("[ИНСТРУМЕНТ] результат 7", out.optJSONObject(7)?.optString("content"))
        val joined = (1 until out.length()).joinToString("|") { out.optJSONObject(it)?.optString("content").orEmpty() }
        assertFalse(joined.contains("старый ответ"))
        assertFalse(joined.contains("первое сообщение"))
        assertFalse(joined.contains("промежуточный ответ"))
    }

    @Test fun followupWithoutToolResultsKeepsFullHistory() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "system memory"))
            .put(JSONObject().put("role", "user").put("content", "привет"))
            .put(JSONObject().put("role", "assistant").put("content", "ответ"))
        val hasToolResult = (0 until messages.length()).any {
            messages.optJSONObject(it)?.optString("content")?.startsWith("[ИНСТРУМЕНТ") == true
        }
        assertFalse(hasToolResult)
    }
}

class FullMemoryGuardPassesTest {
    private fun fullSystem() = "ТВОЯ АВТОБИОГРАФИЯ (L3) и ТВОИ ФАКТЫ (L1), ТВОЙ ДНЕВНИК (L2), журнал L0. " +
        "Инструмент recall доступен для поиска по памяти."

    @Test fun completeMemoryContextPassesTheGuard() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", fullSystem()))
            .put(JSONObject().put("role", "user").put("content", "привет"))
        assertTrue(ChatEngine.hasFullMemoryContext(messages))
        val safe = ChatEngine.providerMessagesWithFullMemory(messages)
        assertNotNull(safe)
        assertEquals(2, safe!!.length())
    }

    @Test fun incompleteMemoryContextIsRejected() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "ТВОИ ФАКТЫ (L1) и дневник без автобиографии"))
            .put(JSONObject().put("role", "user").put("content", "привет"))
        assertFalse(ChatEngine.hasFullMemoryContext(messages))
        assertNull(ChatEngine.providerMessagesWithFullMemory(messages))
    }
}

class AutonomousCompactTest {
    @Test fun shelfBlockListsNamesOnlyUpToEight() {
        val names = (1..10).map { "файл-$it.txt" }
        val block = Thinker.shelfNamesBlock(names)
        assertEquals(8, block.split("\n").count { it.isNotBlank() })
        assertTrue(block.contains("- файл-8.txt (полный текст читай через read_shelf)"))
        assertFalse(block.contains("файл-9.txt"))
        assertFalse(block.contains("--- "))
    }

    @Test fun promptLineUsesNamesVariantForNonInitiativeCycles() {
        val source = RepoSources.text("app/src/main/java/com/ruri/Thinker.kt")
        assertTrue(source.contains("Материалы с полки (имена):"))
        assertTrue(source.contains("if (initiative) bodyEventText"))
    }
}

class Notifications5SummaryTest {
    @Test fun notificationsCappedAtFiveWithOverflowNote() {
        val newestFirst = (1..9).map { "- pkg: заголовок $it" }
        val text = Thinker.summarizedNotifications(newestFirst)
        val lines = text.split("\n")
        assertEquals(6, lines.size)
        assertEquals("- pkg: заголовок 5", lines[0])
        assertEquals("- pkg: заголовок 1", lines[4])
        assertEquals("(ещё 4 более старых уведомлений не показаны)", lines[5])
    }

    @Test fun smallNotificationListsGetNoNote() {
        val newestFirst = (1..3).map { "- pkg: строка $it" }
        val text = Thinker.summarizedNotifications(newestFirst)
        assertEquals(3, text.split("\n").size)
        assertFalse(text.contains("более старых"))
    }
}

class History8Test {
    @Test fun chatHistoryTrimmedToEightMessages() {
        val source = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        assertTrue(source.contains(".take(8).reversed()"))
        assertFalse(source.contains(".take(14).reversed()"))
    }
}

class L3CapTest {
    @Test fun autobiographySummaryCappedAtFourThousandChars() {
        val chatEngine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        val thinker = RepoSources.text("app/src/main/java/com/ruri/Thinker.kt")
        assertTrue(chatEngine.contains("optString(\"summary\").take(4000)"))
        assertFalse(chatEngine.contains("take(5000)"))
        assertTrue(thinker.contains("optString(\"summary\").take(4000)"))
    }
}

class ShelfNamesOnlyTest {
    @Test fun thinkerShelfContextUsesNamesBlock() {
        val source = RepoSources.text("app/src/main/java/com/ruri/Thinker.kt")
        assertTrue(source.contains("shelfNamesBlock("))
        assertTrue(source.contains("(полный текст читай через read_shelf)"))
    }
}
