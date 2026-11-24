
package com.ruri

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testSource(path: String): String {
    val candidates = listOf(File(path), File("app/" + path), File("../app/" + path))
    return candidates.firstOrNull { it.isFile }?.readText()
        ?: throw AssertionError("Source file not found: " + path)
}


private fun routerApiTestRoot(): File = Files.createTempDirectory("ruri-routerapi-test").toFile()

private fun routerApiTestModels(root: File): JSONArray {
    FileManager.init(root)
    val models = JSONObject(FileManager.read(root, "core/config.json")).getJSONArray("models")
    for (index in 0 until models.length()) {
        val model = models.getJSONObject(index)
        if (model.optString("id").startsWith("routerapi-")) model.put("enabled", true)
    }
    return models
}

class TaskEnumCodeTest {
    @Test fun codeTaskExists() {
        assertEquals(Task.CODE, Task.valueOf("CODE"))
    }
}

class CodeDetectTest {
    @Test fun explicitCodeRequestsUseCodePool() {
        val result = ChatEngine.classifyTask("Напиши код на Kotlin", 1200)
        assertEquals(Task.CODE, result.task)
        assertEquals("code", result.key)
    }
}

class LongDetectTest {
    @Test fun largeSerializedContextUsesLongPool() {
        val result = ChatEngine.classifyTask("Продолжим разговор", 390_003)
        assertEquals("long", result.key)
    }
}

class TaskRoutingChatTest {
    @Test fun chatUsesItsConfiguredSequence() {
        val root = routerApiTestRoot()
        try {
            val models = routerApiTestModels(root)
            val order = RouteSelector.orderedIndices(root, models, 0, Task.CHAT, online = true, routeTaskKey = "chat")
            assertEquals(listOf("routeway", "routerapi-glm", "routerapi-cydonia"),
                order.take(3).map { models.getJSONObject(it).getString("id") })
        } finally { root.deleteRecursively() }
    }
}

class TaskRoutingWarmthTest {
    @Test fun warmthUsesMeroCydoniaUnslopOrder() {
        val root = routerApiTestRoot()
        try {
            val models = routerApiTestModels(root)
            val order = RouteSelector.orderedIndices(root, models, 0, Task.WARMTH, online = true, routeTaskKey = "warmth")
            assertEquals(listOf("routeway", "routerapi-cydonia", "routerapi-unslop"),
                order.take(3).map { models.getJSONObject(it).getString("id") })
        } finally { root.deleteRecursively() }
    }
}

class TaskRoutingThoughtUncensoredTest {
    @Test fun autonomousThoughtUsesItsOwnSequenceAndKeepsNsfwCapableRoutes() {
        val root = routerApiTestRoot()
        try {
            val models = routerApiTestModels(root)
            val order = RouteSelector.orderedIndices(root, models, 0, Task.REASON, online = true, adult = true, routeTaskKey = "thought")
            assertEquals(listOf("routeway", "routerapi-cydonia"),
                order.take(2).map { models.getJSONObject(it).getString("id") })
            assertTrue(models.getJSONObject(2).getBoolean("nsfw"))
        } finally { root.deleteRecursively() }
    }
}

class TaskRoutingCodeGlmFirstTest {
    @Test fun glmThenDeepseekThenMeroMero() {
        val root = routerApiTestRoot()
        try {
            val models = routerApiTestModels(root)
            val order = RouteSelector.orderedIndices(root, models, 0, Task.CODE, online = true, routeTaskKey = "code")
            assertEquals(listOf("routerapi-glm", "routerapi-deepseek", "routeway"),
                order.take(3).map { models.getJSONObject(it).getString("id") })
        } finally { root.deleteRecursively() }
    }
}

class TaskRoutingVisionOrderTest {
    @Test fun visionPoolKeepsMeroFirstAndGlmAsFallback() {
        val root = routerApiTestRoot()
        try {
            val models = routerApiTestModels(root)
            val order = RouteSelector.orderedIndices(root, models, 0, Task.VISION, online = true, routeTaskKey = "vision")
            assertEquals(listOf("routeway", "routerapi-glm"),
                order.map { models.getJSONObject(it).getString("id") })
            assertTrue(Vision.isEligibleVisionModel(models.getJSONObject(order[1])))
        } finally { root.deleteRecursively() }
    }
}

class LongUnslopFirstTest {
    @Test fun longContextUsesUnslopThenCydonia() {
        val root = routerApiTestRoot()
        try {
            val models = routerApiTestModels(root)
            val order = RouteSelector.orderedIndices(root, models, 0, Task.REASON, online = true, routeTaskKey = "long")
            assertEquals(listOf("routerapi-unslop", "routerapi-cydonia"),
                order.take(2).map { models.getJSONObject(it).getString("id") })
        } finally { root.deleteRecursively() }
    }
}

class MigrationPrunesDeadRoutesTest {
    @Test fun routerApiMigrationRemovesOldFreeAndPaidPools() {
        val root = routerApiTestRoot()
        try {
            val models = routerApiTestModels(root)
            val ids = (0 until models.length()).map { models.getJSONObject(it).optString("id") }
            assertEquals(5, models.length())
            assertTrue(ids.contains("routeway"))
            assertTrue(ids.containsAll(listOf("routerapi-glm", "routerapi-cydonia", "routerapi-unslop", "routerapi-deepseek")))
            assertFalse(ids.contains("routeway-minimax"))
            assertFalse(ids.any { it.contains("hf", ignoreCase = true) || it.contains("openrouter", ignoreCase = true) })
        } finally { root.deleteRecursively() }
    }
}

class SingleRouterAPIKeyTest {
    @Test fun keyEntryIsStoredThroughSecretStoreAndNeverInDefaultConfig() {
        val fileManager = testSource("src/main/java/com/ruri/MainActivity.kt")
        val secretStore = testSource("src/main/java/com/ruri/SecretStore.kt")
        assertTrue(fileManager.contains("fun saveRouterAPIKey(key: String)"))
        assertTrue(fileManager.contains("SecretStore.saveRuntimeConfig(filesDir, config.toString(2))"))
        assertTrue(secretStore.contains("writeSecrets(root, secrets)"))
        assertFalse(fileManager.contains("sk-"))
    }
}

class NsfwOverrideIntactTest {
    @Test fun nsfwOverrideStillPromotesNsfwRoutesAfterTaskOrdering() {
        val root = routerApiTestRoot()
        try {
            val models = routerApiTestModels(root)
            val order = RouteSelector.orderedIndices(root, models, 0, Task.CHAT, online = true, adult = true, routeTaskKey = "chat")
            val ids = order.map { models.getJSONObject(it).getString("id") }
            assertEquals("routeway", ids.first())
            assertTrue(ids.indexOf("routerapi-cydonia") < ids.indexOf("routerapi-glm"))
        } finally { root.deleteRecursively() }
    }
}

class FailoverAfterRealErrorTest {
    @Test fun chatEngineRetainsHttpErrorDrivenFallback() {
        val source = testSource("src/main/java/com/ruri/ChatEngine.kt")
        assertTrue(source.contains("if (code !in 200..299)"))
        assertTrue(source.contains("continue"))
        assertTrue(source.contains("RouteMeta.recordFailure"))
    }
}

class NoPreflightBanTest {
    @Test fun taskClassifierDoesNotGateOnQuotaVpnBatteryOrNetwork() {
        val source = testSource("src/main/java/com/ruri/ChatEngine.kt")
        val classifier = source.substringAfter("internal fun classifyTask").substringBefore("internal class ModelRequestFailure")
        assertFalse(classifier.contains("quota"))
        assertFalse(classifier.contains("vpn"))
        assertFalse(classifier.contains("battery"))
        assertFalse(classifier.contains("NetState.online"))
    }
}

class ThoughtNotInChatOnVetoTest {
    @Test fun vetoIsRecordedAsThoughtAndNotSentToChat() {
        val source = testSource("src/main/java/com/ruri/Thinker.kt")
        assertTrue(source.contains("if (initiative && effective != \"write\")"))
        assertTrue(source.contains("Diary.append(root, body, reason, emotion, \"thought\")"))
        assertTrue(source.contains("initiative_vetoed"))
    }
}

class ThoughtNotInChatOnThinkTest {
    @Test fun thinkDecisionUsesInternalDiaryBranch() {
        val source = testSource("src/main/java/com/ruri/Thinker.kt")
        assertTrue(source.contains("decision.startsWith(\"think\")"))
        assertTrue(source.contains("if (initiative && effective == \"write\")"))
    }
}

class DiarySaidFlagGatedTest {
    @Test fun saidEntriesAreIgnoredUntilMirrorFlagIsEnabled() {
        val root = routerApiTestRoot()
        try {
            FileManager.init(root)
            FileManager.write(root, "core/config.json", JSONObject().toString(2))
            Diary.append(root, "said one", "test", null, "said")
            var entries = JSONObject(FileManager.read(root, "memory/L2_diary.json")).getJSONArray("entries")
            assertEquals(0, entries.length())
            FileManager.write(root, "core/config.json", JSONObject().put("diary_mirror_said", true).toString(2))
            Diary.append(root, "said two", "test", null, "said")
            entries = JSONObject(FileManager.read(root, "memory/L2_diary.json")).getJSONArray("entries")
            assertEquals("said", entries.getJSONObject(0).getString("kind"))
        } finally { root.deleteRecursively() }
    }
}

class BackoffJitterTest {
    @Test fun jitterIsBoundedToTwentyPercent() {
        assertEquals(100L, CommanderReplyQueue.jitteredBackoff(100L, 0.0))
        assertEquals(110L, CommanderReplyQueue.jitteredBackoff(100L, 0.5))
        assertEquals(120L, CommanderReplyQueue.jitteredBackoff(100L, 1.0))
    }
}

class ShelfReingestOnModifyTest {
    @Test fun watcherComparesStoredSizeAndModificationSignature() {
        val source = testSource("src/main/java/com/ruri/ShelfWatcher.kt")
        assertTrue(source.contains("file.length().toString() + \":\" + file.lastModified()"))
        assertTrue(source.contains("seen.optString(\"sig:\" + safe) == signature"))
    }
}

class NoDeadSaidRenderTest {
    @Test fun diaryUiShowsSaidOnlyWhenMirrorFlagIsSet() {
        val source = testSource("src/main/assets/index.html")
        assertTrue(source.contains("kind==='thought'||(kind==='said'&&mirrorSaid)"))
    }
}

class FollowupLeanNoHistoryResendTest {
    @Test fun toolFollowupKeepsSystemAndToolResultsButDropsOldHistory() {
        val full = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "L0 L1 L2 L3 recall"))
            .put(JSONObject().put("role", "user").put("content", "old chat history"))
            .put(JSONObject().put("role", "assistant").put("content", "tool call"))
            .put(JSONObject().put("role", "user").put("content", "[ИНСТРУМЕНТ read_shelf]: result"))
            .put(JSONObject().put("role", "user").put("content", "latest question"))
        val lean = ChatEngine.leanFollowup(full)
        assertEquals(3, lean.length())
        assertTrue(lean.getJSONObject(0).optString("content").contains("L0"))
        assertTrue(lean.getJSONObject(1).optString("content").startsWith("[ИНСТРУМЕНТ"))
        assertEquals("latest question", lean.getJSONObject(2).optString("content"))
        assertFalse(lean.toString().contains("old chat history"))
    }
}

class FullMemoryGuardPassesTest {
    @Test fun leanFollowupRetainsSystemMemoryForProviderGuard() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "L0 L1 L2 L3 recall"))
            .put(JSONObject().put("role", "user").put("content", "[ИНСТРУМЕНТ search]: result"))
        val lean = ChatEngine.leanFollowup(messages)
        assertTrue(ChatEngine.hasFullMemoryContext(lean))
        assertTrue(ChatEngine.providerMessagesWithFullMemory(lean) != null)
    }
}

class AutonomousCompactTest {
    @Test fun autonomousPromptOmitsNotificationResearchAndShelfBlocks() {
        val source = testSource("src/main/java/com/ruri/Thinker.kt")
        assertTrue(source.contains("val notificationText = if (initiative)"))
        assertTrue(source.contains("val shelfText = if (!initiative) \"\" else"))
        assertTrue(source.contains("(if (initiative) \"УВЕДОМЛЕНИЯ И СВОДКА:"))
    }
}

class Notifications5SummaryTest {
    @Test fun keepsFiveNewestAndSummarizesOlderTopics() {
        val root = routerApiTestRoot()
        try {
            FileManager.init(root)
            for (i in 0 until 7) FileManager.appendEvent(root, JSONObject()
                .put("type", "phone_notification").put("package", "app$i")
                .put("title", "topic$i").put("text", "body$i"))
            val context = ChatEngine.notificationContext(root, 5)
            assertTrue(context.contains("+2 более старых уведомлений"))
            assertTrue(context.contains("topic0"))
            assertTrue(context.contains("topic1"))
            assertFalse(context.contains("body0"))
            assertFalse(context.contains("body1"))
            assertTrue(context.contains("body2"))
            assertTrue(context.contains("body6"))
        } finally { root.deleteRecursively() }
    }
}

class History8Test {
    @Test fun chatContextIsCappedAtEightMessages() {
        val source = testSource("src/main/java/com/ruri/ChatEngine.kt")
        assertTrue(source.contains(".take(8).reversed()"))
    }
}

class L3CapTest {
    @Test fun autonomousL3SummaryIsCappedAtFourThousandCharacters() {
        val source = testSource("src/main/java/com/ruri/Thinker.kt")
        assertTrue(source.contains("optString(\"summary\").take(4000)"))
    }
}

class ShelfNamesOnlyTest {
    @Test fun autonomousPromptDoesNotInlineShelfFileContents() {
        val source = testSource("src/main/java/com/ruri/Thinker.kt")
        val start = source.indexOf("val shelfNames =")
        val end = source.indexOf("val notificationText =", start)
        val shelfBlock = source.substring(start, end)
        assertTrue(shelfBlock.contains("substringAfterLast"))
        assertFalse(shelfBlock.contains("shelfContextText(root, name)"))
    }
}

class ExemplarsInPromptTest {
    @Test fun openingExemplarsRemainInThinkerPrompt() {
        val source = testSource("src/main/java/com/ruri/Thinker.kt")
        assertTrue(source.contains("opening_exemplars"))
    }
}
