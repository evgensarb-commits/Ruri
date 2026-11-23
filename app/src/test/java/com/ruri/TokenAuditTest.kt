package com.ruri

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenAuditTest {
    @Test
    fun aggregatesUsageAndBreakdownByStageWithoutWritingIntoL0() {
        val root = Files.createTempDirectory("ruri-token-audit").toFile()
        try {
            val usage = JSONObject()
                .put("prompt_tokens", 100)
                .put("completion_tokens", 20)
                .put("total_tokens", 120)
                .put("prompt_tokens_details", JSONObject().put("cached_tokens", 30))
            val breakdown = TokenAudit.breakdownLengths("system" to 800, "history" to 200)

            TokenAudit.record(root, "initial", "route-a", "model-a", usage, breakdown, 4, 0, 1, "msg-1")
            TokenAudit.record(root, "tool_hop", "route-a", "model-a",
                JSONObject().put("prompt_tokens", 150).put("completion_tokens", 10).put("total_tokens", 160),
                TokenAudit.breakdownLengths("tool_results" to 90), 6, 1, 1, "msg-1")

            val summary = TokenAudit.aggregate(root)
            val stages = summary.getJSONObject("stages")
            val initial = stages.getJSONObject("initial")
            assertEquals(1, initial.getInt("count"))
            assertEquals(1, initial.getInt("known_usage"))
            assertEquals(100L, initial.getLong("prompt"))
            assertEquals(20L, initial.getLong("completion"))
            assertEquals(120L, initial.getLong("total"))
            assertEquals(30L, initial.getLong("cached"))
            assertEquals(800L, initial.getJSONObject("breakdown").getLong("system_chars"))
            assertEquals(2, stages.getJSONObject("tool_hop").getInt("count"))
                .let { /* stage count is one event; assertion below checks aggregate totals */ }
            assertEquals(1, stages.getJSONObject("tool_hop").getInt("count"))
            assertEquals(150L, stages.getJSONObject("tool_hop").getLong("prompt"))
            assertEquals(2, summary.getInt("events"))
            assertTrue(FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 10).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingProviderUsageRemainsUnknownInsteadOfBeingReportedAsZeroTokens() {
        val root = Files.createTempDirectory("ruri-token-audit-unknown").toFile()
        try {
            TokenAudit.record(root, "retry_after_error", "route-a", "model-a", null, null, 3, 0, 2)
            val row = TokenAudit.aggregate(root).getJSONObject("stages").getJSONObject("retry_after_error")
            assertEquals(0, row.getInt("known_usage"))
            assertEquals(0L, row.getLong("prompt"))
            assertEquals(1, row.getInt("count"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exportContainsOnlyAuditMetadataAndNoPromptText() {
        val root = Files.createTempDirectory("ruri-token-audit-export").toFile()
        try {
            TokenAudit.record(root, "initial", "route-a", "model-a",
                JSONObject().put("prompt_tokens", 7), TokenAudit.breakdown("system" to "PRIVATE PROMPT"), 2, 0, 1)
            val exported = TokenAudit.export(root)
            assertTrue(exported.contains("initial"))
            assertTrue(exported.contains("system_chars"))
            assertFalse(exported.contains("PRIVATE PROMPT"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun breakdownCountsCharactersOnly() {
        val value = TokenAudit.breakdown("system" to "абв", "history" to "hello")
        assertEquals(3, value.getInt("system_chars"))
        assertEquals(5, value.getInt("history_chars"))
    }
}
