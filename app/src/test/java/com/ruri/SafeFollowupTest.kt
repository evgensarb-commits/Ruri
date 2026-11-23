package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeFollowupTest {
    private fun msg(role: String, content: String, discardable: Boolean = false): JSONObject =
        JSONObject().put("role", role).put("content", content).apply {
            if (discardable) put(FollowupContextBuilder.DISCARDABLE_FIELD, true)
        }

    private fun chain(): JSONArray = JSONArray()
        .put(msg("system", "SYSTEM_FULL_MEMORY_MARKER L0 L1 L2 L3 recall"))
        .put(msg("user", "предыдущая реплика Командира с важным условием"))
        .put(msg("assistant", "{\"action\":\"search\",\"query\":\"X\"}"))
        .put(msg("user", "[ИНСТРУМЕНТ search]: {\"results\":[{\"url\":\"https://a/x\"}]}"))
        .put(msg("assistant", "{\"action\":\"read_url\",\"url\":\"https://a/x\"}"))
        .put(msg("user", "[ИНСТРУМЕНТ read_url]: ТЕЛО_СТРАНИЦЫ_7600"))
        .put(msg("assistant", "{\"action\":\"read_shelf\",\"name\":\"doc.docx\"}"))
        .put(msg("user", "[ИНСТРУМЕНТ read_shelf]: ТЕЛО_DOCX"))
        .put(msg("user", "ответь прозой"))

    private fun contents(messages: JSONArray): List<String> =
        (0 until messages.length()).map { messages.getJSONObject(it).optString("content") }

    @Test
    fun keepsAllToolResultsAndAssistantDecisionsInOriginalOrder() {
        val lean = FollowupContextBuilder.lean(chain())
        val values = contents(lean)
        val tools = values.filter { it.startsWith("[ИНСТРУМЕНТ") }
        assertEquals(3, tools.size)
        assertTrue(tools[0].contains("https://a/x"))
        assertTrue(tools[1].contains("ТЕЛО_СТРАНИЦЫ_7600"))
        assertTrue(tools[2].contains("ТЕЛО_DOCX"))
        assertTrue(values.indexOfFirst { it.contains("\"action\":\"search\"") } <
            values.indexOfFirst { it.contains("\"action\":\"read_url\"") })
        assertTrue(values.indexOfFirst { it.contains("\"action\":\"read_url\"") } <
            values.indexOfFirst { it.contains("\"action\":\"read_shelf\"") })
    }

    @Test
    fun preservesUnmarkedCommanderHistoryByDefault() {
        val lean = FollowupContextBuilder.lean(chain())
        assertTrue(contents(lean).any { it.contains("важным условием") })
        assertEquals(chain().length(), lean.length())
    }

    @Test
    fun dropsOnlyExplicitlyMarkedNonFinalUserMessages() {
        val full = chain()
        full.put(1, msg("user", "избыточная техническая реплика", discardable = true))
        val lean = FollowupContextBuilder.lean(full)
        assertFalse(contents(lean).any { it.contains("избыточная техническая реплика") })
        assertTrue(contents(lean).any { it.contains("важным условием") }.not())
        assertTrue((0 until lean.length()).none {
            lean.getJSONObject(it).has(FollowupContextBuilder.DISCARDABLE_FIELD)
        })
    }

    @Test
    fun systemAssistantToolResultsAndFinalUserAreNeverDroppedEvenIfMarked() {
        val full = JSONArray()
            .put(msg("system", "SYSTEM_FULL_MEMORY_MARKER L0 L1 L2 L3 recall", true))
            .put(msg("assistant", "intermediate decision", true))
            .put(msg("user", "[ИНСТРУМЕНТ read_url]: dependency", true))
            .put(msg("user", "final instruction", true))
        val lean = FollowupContextBuilder.lean(full)
        assertEquals(4, lean.length())
        assertEquals("SYSTEM_FULL_MEMORY_MARKER L0 L1 L2 L3 recall", lean.getJSONObject(0).optString("content"))
        assertTrue(contents(lean).contains("intermediate decision"))
        assertTrue(contents(lean).contains("[ИНСТРУМЕНТ read_url]: dependency"))
        assertTrue(contents(lean).contains("final instruction"))
    }

    @Test
    fun fullMemoryGuardStillPassesWithoutUsingItAsProofOfSemanticCompleteness() {
        val lean = FollowupContextBuilder.lean(chain())
        assertTrue(ChatEngine.hasFullMemoryContext(lean))
    }

    @Test
    fun savesCharactersOnlyWhenCallerProvidesExplicitDiscardabilityEvidence() {
        val full = chain().put(msg("user", "x".repeat(2000), discardable = true))
        val lean = FollowupContextBuilder.lean(full)
        assertTrue(FollowupContextBuilder.chars(lean) < FollowupContextBuilder.chars(full))
    }
}
