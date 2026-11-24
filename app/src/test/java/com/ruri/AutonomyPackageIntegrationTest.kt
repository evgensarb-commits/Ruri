package com.ruri

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomyPackageIntegrationTest {
    private fun source(path: String): String {
        val candidates = listOf(File(path), File("app/" + path), File("../app/" + path))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("Source file not found: " + path)
    }

    @Test
    fun lifeStateEngineHasNoActivityOrMaintenanceDiaryWriters() {
        val lifeState = source("src/main/java/com/ruri/LifeStateEngine.kt")
        assertFalse(lifeState.contains("appendActivityDiary"))
        assertFalse(lifeState.contains("appendMaintenanceDiary"))
    }

    @Test
    fun diaryUiFiltersLegacyEntriesAndPrimaryNavigationHasOnlyChatAndDiary() {
        val html = source("src/main/assets/index.html")
        assertTrue(html.contains("data-pane=" + "\"chat\""))
        assertTrue(html.contains("data-pane=" + "\"diary\""))
        assertFalse(html.contains("data-pane=" + "\"search\""))
        assertFalse(html.contains("data-pane=" + "\"shelf\""))
        assertTrue(html.contains("e.kind==='said'"))
    }

    @Test
    fun automaticShelfWatcherIsWiredToServiceWorkerAndChatAttachment() {
        val watcher = source("src/main/java/com/ruri/ShelfWatcher.kt")
        val service = source("src/main/java/com/ruri/RuriService.kt")
        val scheduler = source("src/main/java/com/ruri/LifeScheduler.kt")
        val activity = source("src/main/java/com/ruri/MainActivity.kt")
        assertTrue(watcher.contains("FileObserver.CLOSE_WRITE"))
        assertTrue(service.contains("ShelfWatcher.start(this)"))
        assertTrue(scheduler.contains("ShelfWatcher.scan(appContext)"))
        assertTrue(scheduler.contains("ShelfWatcher.ingestPending(appContext, pending)"))
        assertTrue(activity.contains("fun saveShelfFile"))
        assertTrue(activity.contains("ShelfWatcher.markAdded"))
    }

    @Test
    fun googleSearchDoesNotFallBackToDuckDuckGo() {
        val webSearch = source("src/main/java/com/ruri/WebSearch.kt").lowercase()
        assertTrue(webSearch.contains("customsearch/v1"))
        assertTrue(webSearch.contains("google.com/search"))
        assertFalse(webSearch.contains("duckduckgo"))
    }

    @Test
    fun chatHistoryUsesExpandedReadWindowWithoutMutatingAppendOnlyL0() {
        val fileManager = source("src/main/java/com/ruri/FileManager.kt")
        val html = source("src/main/assets/index.html")
        val activity = source("src/main/java/com/ruri/MainActivity.kt")
        assertTrue(fileManager.contains("limit: Int = 1000"))
        assertTrue(fileManager.contains("limit.coerceIn(1, 1000)"))
        assertTrue(fileManager.contains("maxLines = 5000"))
        assertTrue(html.contains("Ruri.readLastEvents('message',1000)"))
        assertTrue(activity.contains("maxLines = 1000"))
        assertTrue(fileManager.contains("fun appendEvent"))
    }

    @Test
    fun scheduledDiaryThoughtsHaveAnIndependentCadence() {
        val diary = source("src/main/java/com/ruri/Diary.kt")
        val scheduler = source("src/main/java/com/ruri/LifeScheduler.kt")
        val emotion = source("src/main/java/com/ruri/EmotionEngine.kt")
        assertTrue(diary.contains("fun lastThoughtAt"))
        assertTrue(scheduler.contains("thoughtCheckDue"))
        assertTrue(scheduler.contains("last_thought_check_at"))
        assertTrue(scheduler.contains("thought_interval_h\", 2.0"))
        assertTrue(scheduler.contains("eventDriven = reason.startsWith"))
        assertTrue(scheduler.contains("return driveCheckEvent || notificationEvent || explicitSelfTask || thoughtCheckEvent || eventDriven"))
        assertTrue(emotion.contains("thought_interval_h\", 2.0"))
        assertTrue(emotion.contains("thought_cadence_v2"))
        val thinker = source("src/main/java/com/ruri/Thinker.kt")
        assertTrue(thinker.contains("попытка останется в очереди"))
        assertTrue(thinker.contains("Diary.thoughtCount(root)"))
    }

    @Test
    fun runtimeMigrationKeepsOnlyMeroMeroAndNemotron() {
        val fileManager = source("src/main/java/com/ruri/FileManager.kt")
        val routeSelector = source("src/main/java/com/ruri/RouteSelector.kt")
        assertTrue(fileManager.contains("nsfw_only_routes_v2"))
        assertTrue(fileManager.contains("gemma-4-26b-a4b-it-meromero:free"))
        assertTrue(fileManager.contains("BlockRun · Nemotron 3 Nano Omni Free"))
        assertTrue(fileManager.contains("https://blockrun.ai/api/v1"))
        assertTrue(fileManager.contains("api_key_optional"))
        val chatEngine = source("src/main/java/com/ruri/ChatEngine.kt")
        val thinker = source("src/main/java/com/ruri/Thinker.kt")
        assertTrue(chatEngine.contains("!model.optBoolean(\"api_key_optional\", false)"))
        assertTrue(thinker.contains("!model.optBoolean(\"api_key_optional\", false)"))
        assertTrue(thinker.contains("thought_check"))
        assertFalse(fileManager.contains("integrate.api.nvidia.com"))
        assertTrue(fileManager.contains("free_only"))
        assertFalse(fileManager.contains("huggingface.co"))
        assertFalse(fileManager.contains("openrouter.ai"))
        assertTrue(fileManager.contains("RouteMeta.pruneToConfiguredRoutes"))
        assertTrue(routeSelector.contains("compareBy<Int>"))
        assertTrue(routeSelector.contains("nsfw_override"))
        assertTrue(routeSelector.contains("Task.VISION"))
        assertFalse(fileManager.contains("Requesty · Google Gemma 4 Free"))
    }
}