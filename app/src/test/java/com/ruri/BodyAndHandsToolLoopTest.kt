package com.ruri

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

abstract class BodyHandsTestBase {
    protected lateinit var root: File
    @Before fun createRoot() { root = Files.createTempDirectory("ruri-body-hands").toFile() }
    @After fun removeRoot() { root.deleteRecursively() }
    protected fun events(type: String): List<JSONObject> =
        FileManager.readLastLines(root, "memory/L0_raw.jsonl", 2000)
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { it.optString("type") == type }
}

class CapabilitiesMigrationTest : BodyHandsTestBase() {
    @Test fun initCreatesCapabilitiesAndSynchronizesSelfModel() {
        FileManager.init(root)
        assertTrue(File(root, "core/capabilities.json").isFile)
        val capabilities = JSONObject(FileManager.read(root, "core/capabilities.json"))
        val self = JSONObject(FileManager.read(root, "memory/self_model.json"))
        val known = self.getJSONArray("known_capabilities")
        val limits = self.getJSONArray("limitations")
        assertTrue(known.length() >= capabilities.getJSONArray("senses").length() + capabilities.getJSONArray("actions").length())
        assertEquals(capabilities.getJSONArray("limits").length(), limits.length())
        assertTrue(known.toString().contains("Полка"))
    }
}

class PromptBodySectionTest : BodyHandsTestBase() {
    @Test fun bodySectionDescribesBodyMemoryShelfNotificationsAndSearch() {
        Capabilities.ensure(root)
        val prompt = Capabilities.bodySection(root)
        assertTrue(prompt.contains("ТВОЁ ТЕЛО"))
        assertTrue(prompt.contains("помнишь между сессиями"))
        assertTrue(prompt.contains("полке"))
        assertTrue(prompt.contains("уведомления"))
        assertTrue(prompt.contains("память"))
        assertTrue(prompt.contains("поиск"))
        assertTrue(prompt.contains("одним JSON-объектом"))
    }
}

class ToolProtocolParseTest {
    @Test fun parsesPlainAndFencedJsonAndRejectsProse() {
        assertEquals("search", ChatEngine.parseAction("""{"action":"search","query":"котики"}""")?.optString("action"))
        assertEquals("read_shelf", ChatEngine.parseAction(""+
            "```json\n{\"action\":\"read_shelf\",\"name\":\"notes.txt\"}\n```"
        )?.optString("action"))
        assertNull(ChatEngine.parseAction("Сейчас поищу: {\"action\":\"search\",\"query\":\"x\"}"))
        assertNull(ChatEngine.parseAction("""{"action":"search","query":"x","extra":"bad"}"""))
        assertNull(ChatEngine.parseAction("""{"action":"search","query":"${"x".repeat(301)}"}"""))
    }
}

class ToolLoopExecutesAndReasksTest : BodyHandsTestBase() {
    @Test fun actionIsExecutedReaskedAndFinalReplyIsAppendedOnce() {
        FileManager.write(root, "shelf/hello.txt", "hello shelf")
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "system"))
        var modelCalls = 1
        val reply = ChatEngine.runToolLoop(root, """{"action":"list_shelf"}""", messages) { next ->
            modelCalls++
            assertTrue(next.getJSONObject(next.length() - 1).optString("content").startsWith("[ИНСТРУМЕНТ list_shelf]:"))
            "Полка проверена."
        }
        ChatEngine.appendFinalReply(root, reply)
        assertEquals("Полка проверена.", reply)
        assertEquals(2, modelCalls)
        assertEquals(1, events("tool_call").size)
        assertEquals(1, events("tool_result").size)
        assertEquals(1, events("message").count { it.optString("who") == "ruri" })
    }
}

class ToolHopLimitTest : BodyHandsTestBase() {
    @Test fun permitsSequentialActionsForLongDocument() {
        val messages = JSONArray()
        var reasks = 0
        val repeated = """{"action":"list_shelf"}"""
        val reply = ChatEngine.runToolLoop(root, repeated, messages) {
            reasks++
            repeated
        }
        assertEquals(64, reasks)
        assertEquals(64, events("tool_call").size)
        assertTrue(reply.contains("Файл прочитан не полностью"))
        assertTrue((0 until messages.length()).any {
            messages.getJSONObject(it).optString("content").contains("Лимит инструментов исчерпан")
        })
    }
}

class KiloRemovedTest : BodyHandsTestBase() {
    @Test fun migrationRemovesKiloAndEveryMinimalOrAnonymousRoute() {
        FileManager.init(root)
        val config = JSONObject(SecretStore.readRuntimeConfig(root))
        val models = config.getJSONArray("models")
        for (i in 0 until models.length()) {
            val model = models.getJSONObject(i)
            assertFalse(model.optString("id").equals("kilo", true))
            assertFalse(model.optBoolean("minimal_context", false))
            assertFalse(model.optBoolean("allow_anonymous", false))
        }
        assertFalse(config.toString().contains("kilo-auto"))
        assertTrue(config.optBoolean("free_only"))
        assertTrue(FreeModelRouter.orderedIndices(root, models, config.optInt("current", 0), Task.CHAT)
            .all { !models.getJSONObject(it).optBoolean("minimal_context", false) })
    }

    @Test fun emptyRouteSetQueuesReplyInsteadOfSendingAmnesicRequest() {
        FileManager.init(root)
        FileManager.write(root, "core/config.json", JSONObject()
            .put("models", JSONArray()).put("current", 0).put("free_only", true).toString())
        FileManager.write(root, "memory/L4_state.json", JSONObject().toString())
        val messageId = "empty-route-commander-message"
        FileManager.appendEvent(root, JSONObject().put("id", messageId)
            .put("type", "message").put("who", "commander").put("text", "Ответь мне")
            .put("ts", System.currentTimeMillis()))
        CommanderReplyQueue.enqueue(root, messageId)
        val outcome = runCatching { ChatEngine.respond(root, messageId) }
        assertTrue(outcome.isFailure)
        assertTrue(CommanderReplyQueue.hasPending(root))
        assertTrue(OfflineQueue.hasPending(root))
    }
}

class FullMemoryEveryRouteTest : BodyHandsTestBase() {
    @Test fun providerPayloadRequiresL0AndEveryLongTermMemoryLayer() {
        val full = JSONArray()
            .put(JSONObject().put("role", "system").put("content",
                "ТВОЯ ПАМЯТЬ L0 история L1 факты L2 дневник L3 автобиография recall"))
            .put(JSONObject().put("role", "user").put("content", "Помнишь?"))
        assertTrue(ChatEngine.hasFullMemoryContext(full))
        val providers = listOf("Routeway", "Requesty")
        providers.forEach { _ ->
            val payload = ChatEngine.providerMessagesWithFullMemory(full)
            assertNotNull(payload)
            assertTrue(ChatEngine.hasFullMemoryContext(payload!!))
        }
        val amnesic = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "только текущая реплика"))
            .put(JSONObject().put("role", "user").put("content", "Помнишь?"))
        assertFalse(ChatEngine.hasFullMemoryContext(amnesic))
    }
}

class ToolResultRedactionTest : BodyHandsTestBase() {
    @Test fun shelfSecretIsRedactedBeforeL0AndModelRequest() {
        FileManager.write(root, "shelf/private.txt", "пароль: hunter2")
        val messages = JSONArray()
        var modelContent = ""
        ChatEngine.runToolLoop(root, """{"action":"read_shelf","name":"private.txt"}""", messages) { next ->
            modelContent = next.getJSONObject(next.length() - 1).optString("content")
            "Прочитано."
        }
        val stored = events("tool_result").single().optString("text")
        assertTrue(stored.contains(SecretRedactor.PLACEHOLDER))
        assertFalse(stored.contains("hunter2"))
        assertTrue(modelContent.contains(SecretRedactor.PLACEHOLDER))
        assertFalse(modelContent.contains("hunter2"))
    }
}

class RememberFactGuardTest : BodyHandsTestBase() {
    @Test fun selfReportedFactsAreCappedDeduplicatedAndConfidenceLimited() {
        val action = """{"action":"remember_fact","text":"Командир любит чай с бергамотом"}"""
        repeat(2) {
            ChatEngine.runToolLoop(root, action, JSONArray()) { "Запомнила." }
        }
        val facts = JSONObject(FileManager.read(root, "memory/L1_facts.json")).getJSONArray("facts")
        assertEquals(1, facts.length())
        val fact = facts.getJSONObject(0)
        assertEquals("self_reported", fact.optString("origin"))
        assertTrue(fact.optDouble("confidence", 1.0) <= 0.6)
    }
}
