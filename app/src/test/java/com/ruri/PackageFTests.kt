package com.ruri

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun packageFRoot(prefix: String): File =
    Files.createTempDirectory(prefix).toFile().also { FileManager.init(it) }

class BalancedBraceStripTest {
    @Test fun nestedObjectsAndBracesInsideStringsAreExtractedAsOneAction() {
        val action = """{"action":"read_shelf","name":"notes.txt","options":{"literal":"a } and { b","nested":{"ok":true}}}"""
        val (clean, diary, tools) = ToolLoop.stripActions("До действия $action после действия")
        assertEquals("До действия  после действия", clean)
        assertTrue(diary.isEmpty())
        assertEquals(1, tools.size)
        assertEquals("read_shelf", tools.single().optString("action"))
        assertEquals("a } and { b", tools.single().getJSONObject("options").optString("literal"))
    }
}

class OnlyJsonFallbackTest {
    @Test fun onlyJsonIsNeverConsideredVisibleProse() {
        assertNull(ToolLoop.proseCandidate("""{"action":"search","query":"коты"}"""))
        assertNull(ToolLoop.proseCandidate("""{"summary":"only json"}"""))
        assertEquals("Обычный ответ.", ToolLoop.proseCandidate("Обычный ответ."))
    }
}

class NoBatteryGateTest {
    @Test fun liveNeedIsIndependentOfBatteryAndOnlyCommanderSilenceChangesConnectionNeed() {
        val now = System.currentTimeMillis()
        val recent = LifeStateEngine.calculateNeeds(now - 60_000L, now)
        val silent = LifeStateEngine.calculateNeeds(now - 24L * 60L * 60L * 1000L, now)
        assertEquals(0.38, recent.getDouble("live"), 0.0)
        assertEquals(0.38, silent.getDouble("live"), 0.0)
        assertTrue(silent.getDouble("love_commander") > recent.getDouble("love_commander"))
    }
}

class NoVpnPreFilterTest {
    @Test fun eligibleFreeRouteSurvivesOfflineAndVpnMetadata() {
        val root = packageFRoot("ruri-no-vpn-prefilter")
        try {
            val models = JSONArray().put(
                JSONObject()
                    .put("id", "vpn-route")
                    .put("name", "VPN route")
                    .put("api_key", "test-key")
                    .put("free_tier", true)
                    .put("enabled", true)
                    .put("needs_vpn", true)
            )
            val order = RouteSelector.orderedIndices(
                root, models, 0, Task.CHAT, online = false, vpn = false
            )
            assertEquals(listOf(0), order)
        } finally {
            root.deleteRecursively()
        }
    }
}

class DiaryThoughtsOnlyTest {
    @Test fun defaultDiaryViewIncludesThoughtsAndLegacyEntriesButNotSaid() {
        val selected = MemoryConsolidator.thoughtEntries(
            listOf(
                JSONObject().put("kind", "thought").put("text", "мысль"),
                JSONObject().put("kind", "said").put("text", "сказанное"),
                JSONObject().put("text", "старая запись без kind")
            )
        )
        assertEquals(listOf("мысль", "старая запись без kind"), selected.map { it.optString("text") })
    }
}
