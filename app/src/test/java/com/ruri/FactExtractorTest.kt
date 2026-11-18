package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactExtractorTest {
    @Test
    fun acceptsOnlyHighConfidenceFactsWithCommanderEvidence() {
        val facts = JSONArray()
        val candidates = JSONArray()
            .put(
                JSONObject()
                    .put("text", "Командир живёт в Рубе и часто бывает в Лилле")
                    .put("category", "personal_context")
                    .put("confidence", 0.93)
                    .put("explicit", true)
                    .put("evidence_event_ids", JSONArray().put("commander-1").put("assistant-1"))
            )
            .put(
                JSONObject()
                    .put("text", "Предпочитает получать ответы на русском языке")
                    .put("confidence", 0.70)
                    .put("explicit", true)
                    .put("evidence_event_ids", JSONArray().put("commander-1"))
            )
            .put(
                JSONObject()
                    .put("text", "Вероятно любит горы")
                    .put("confidence", 0.95)
                    .put("explicit", false)
                    .put("evidence_event_ids", JSONArray().put("commander-1"))
            )
            .put(
                JSONObject()
                    .put("text", "Пароль: hunter2")
                    .put("confidence", 0.99)
                    .put("explicit", true)
                    .put("evidence_event_ids", JSONArray().put("commander-1"))
            )
            .put(
                JSONObject()
                    .put("text", "Говорит по-французски и по-русски")
                    .put("confidence", 0.91)
                    .put("explicit", false)
                    .put("evidence_event_ids", JSONArray().put("assistant-1").put("assistant-2"))
            )

        val result = FactExtractor.applyCandidates(
            facts = facts,
            candidates = candidates,
            commanderIds = setOf("commander-1", "commander-2"),
            sourceModel = "Gemini",
            now = 123456L
        )

        assertEquals(1, result.added)
        assertEquals(0, result.updated)
        assertEquals(1, facts.length())
        val stored = facts.getJSONObject(0)
        assertTrue(stored.optString("text").contains("Рубе"))
        assertEquals("Gemini", stored.optString("source_model"))
        assertEquals(1, stored.getJSONArray("source_event_ids").length())
        assertEquals("commander-1", stored.getJSONArray("source_event_ids").getString(0))
    }

    @Test
    fun matchingFactIsUpdatedAndEvidenceIdsAreMergedWithoutDuplicates() {
        val facts = JSONArray().put(
            JSONObject()
                .put("id", "fact-existing")
                .put("text", "Командир предпочитает спокойные ответы")
                .put("confidence", 0.84)
                .put("source_event_ids", JSONArray().put("commander-1"))
                .put("created_at", 100L)
        )
        val candidates = JSONArray().put(
            JSONObject()
                .put("text", "Командир ПРЕДПОЧИТАЕТ спокойные ответы!")
                .put("confidence", 0.96)
                .put("explicit", true)
                .put("evidence_event_ids", JSONArray().put("commander-1").put("commander-2").put("commander-2"))
        )

        val result = FactExtractor.applyCandidates(
            facts, candidates, setOf("commander-1", "commander-2"), "Groq", 200L
        )

        assertEquals(0, result.added)
        assertEquals(1, result.updated)
        assertEquals(1, facts.length())
        val stored = facts.getJSONObject(0)
        assertEquals("fact-existing", stored.optString("id"))
        assertEquals(0.96, stored.optDouble("confidence"), 0.0001)
        assertEquals(2, stored.getJSONArray("source_event_ids").length())
        assertEquals(200L, stored.optLong("last_confirmed_at"))
    }

    @Test
    fun personalContextIsNotTreatedAsAuthenticationSecret() {
        val personal = "Командир сообщил email commander@example.com и номер телефона."
        assertFalse(CredentialGuard.containsCredentials(personal))
    }
}
