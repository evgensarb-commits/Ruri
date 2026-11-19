package com.ruri

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    @Test fun permitsThreeActionsAndOneFinalRequest() {
        val messages = JSONArray()
        var reasks = 0
        val repeated = """{"action":"list_shelf"}"""
        val reply = ChatEngine.runToolLoop(root, repeated, messages) {
            reasks++
            repeated
        }
        assertEquals(3, reasks)
        assertEquals(3, events("tool_call").size)
        assertEquals(repeated, reply)
        assertTrue((0 until messages.length()).any {
            messages.getJSONObject(it).optString("content").contains("Лимит инструментов исчерпан")
        })
    }
}

class KiloRemovedTest : BodyHandsTestBase() {
    @Test fun routerExcludesKiloAnonymousAndMinimalContextRoutes() {
        val models = JSONArray()
            .put(JSONObject().put("id", "routeway").put("free_tier", true).put("enabled", true).put("api_key", "rw"))
            .put(JSONObject().put("id", "kilo").put("free_tier", true).put("enabled", true).put("allow_anonymous", true).put("minimal_context", true))
            .put(JSONObject().put("id", "requesty").put("free_tier", true).put("enabled", true).put("api_key", "rq"))
            .put(JSONObject().put("id", "unsafe").put("free_tier", true).put("enabled", true).put("api_key", "x").put("minimal_context", true))
        assertTrue(FreeModelRouter.isDisallowedMemoryRoute(models.getJSONObject(1)))
        assertTrue(FreeModelRouter.isDisallowedMemoryRoute(models.getJSONObject(3)))
        assertEquals(listOf(0, 2), FreeModelRouter.orderedIndices(models, 0))
    }

    @Test fun noAnonymousOrMinimalPayloadCanBeSent() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "full memory"))
        val anonymous = JSONObject().put("free_tier", true).put("enabled", true).put("allow_anonymous", true)
        val minimal = JSONObject().put("free_tier", true).put("enabled", true).put("api_key", "x").put("minimal_context", true)
        listOf(anonymous, minimal).forEach { model ->
            var rejected = false
            try { ChatEngine.providerMessagesForRoute(messages, model) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
    }

    @Test fun unavailableRoutesSetPendingReplyAndReturnSpecificCause() {
        FileManager.init(root)
        var reason = ""
        try { ChatEngine.failUnavailable(root, "Routeway: DNS недоступен") }
        catch (e: IllegalStateException) { reason = e.message.orEmpty() }
        assertTrue(reason.contains("Routeway"))
        assertTrue(reason.contains("DNS"))
        assertTrue(OfflineQueue.hasPending(root))
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


class FullMemoryEveryRouteTest : BodyHandsTestBase() {
    @Test fun everyFreeRouteGetsL0HistoryL1L2L3AndRecall() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "L1_FACTS\nL2_DIARY\nL3_SUMMARY"))
            .put(JSONObject().put("role", "system").put("content", "RECALL_RESULT"))
            .put(JSONObject().put("role", "user").put("content", "L0_HISTORY"))
        val models = listOf(
            JSONObject().put("id", "routeway").put("free_tier", true).put("enabled", true).put("api_key", "rw"),
            JSONObject().put("id", "requesty").put("free_tier", true).put("enabled", true).put("api_key", "rq")
        )
        models.forEach { model ->
            val payload = ChatEngine.providerMessagesForRoute(messages, model)
            val content = (0 until payload.length()).joinToString("\n") {
                payload.getJSONObject(it).optString("content")
            }
            assertTrue(content.contains("L0_HISTORY"))
            assertTrue(content.contains("L1_FACTS"))
            assertTrue(content.contains("L2_DIARY"))
            assertTrue(content.contains("L3_SUMMARY"))
            assertTrue(content.contains("RECALL_RESULT"))
        }
    }
}