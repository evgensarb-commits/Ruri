package com.ruri

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSelectorTest {
    @Test
    fun keepsConfiguredPrimaryAndScoreRanksFallbacksWithoutPaidRoutes() {
        val root = Files.createTempDirectory("ruri-route-selector").toFile()
        try {
            val models = JSONArray()
                .put(JSONObject().put("id", "route-a").put("free_tier", true).put("enabled", true)
                    .put("api_key", "configured").put("priority", 1))
                .put(JSONObject().put("id", "route-b").put("free_tier", true).put("enabled", true)
                    .put("api_key", "configured").put("priority", 2))
                .put(JSONObject().put("id", "paid").put("free_tier", false).put("enabled", true)
                    .put("api_key", "configured").put("priority", 0))

            RouteMeta.update(root, "route-a") { it.getJSONObject("scores").put(Task.WARMTH.name, 0.1) }
            RouteMeta.update(root, "route-b") { it.getJSONObject("scores").put(Task.WARMTH.name, 0.9) }

            val order = RouteSelector.orderedIndices(
                root, models, current = 0, task = Task.WARMTH, online = true, vpn = false
            )
            assertEquals(listOf(0, 1), order)
            val fallbackScores = RouteSelector.orderedIndices(
                root, models, current = -1, task = Task.WARMTH, online = true, vpn = false
            )
            assertEquals(listOf(1, 0), fallbackScores)
            assertTrue(order.none { models.getJSONObject(it).optString("id") == "paid" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun classifiesPerMinute429AsRateLimitAndQuota429AsQuota() {
        assertEquals("rate_limit", RouteSelector.classify(429, null, "Too many requests per minute"))
        assertEquals("rate_limit", RouteSelector.classify(429, null, "RPM limit"))
        assertEquals("quota", RouteSelector.classify(429, null, "token quota exhausted"))
        assertEquals("rate_limit", RouteSelector.classify(429, null, "Too many requests"))
    }

    @Test
    fun vpnAndReachabilityMetadataNeverBlockConfiguredFreeRoutes() {
        val root = Files.createTempDirectory("ruri-vpn-routes").toFile()
        try {
            val models = JSONArray()
                .put(JSONObject().put("id", "vpn-only").put("free_tier", true).put("enabled", true)
                    .put("api_key", "configured").put("priority", 1))
                .put(JSONObject().put("id", "ru-route").put("free_tier", true).put("enabled", true)
                    .put("api_key", "configured").put("priority", 2))
            RouteMeta.setNeedsVpn(root, "vpn-only", true)
            RouteMeta.update(root, "vpn-only") { it.put("ru_reachable", false) }

            assertEquals(
                listOf(0, 1),
                RouteSelector.orderedIndices(root, models, 0, Task.CHAT, online = true, vpn = false)
            )
            assertEquals(
                listOf(0, 1),
                RouteSelector.orderedIndices(root, models, 0, Task.CHAT, online = true, vpn = true)
            )
        } finally {
            root.deleteRecursively()
        }
    }

}

class OnlyTwoRoutesTest {
    @Test fun migrationKeepsOnlyMeroMeroAndMiniMaxAndPrunesOldMetadata() {
        val root = Files.createTempDirectory("ruri-only-two-routes").toFile()
        try {
            FileManager.init(root)
            val config = JSONObject(FileManager.read(root, "core/config.json"))
            val oldModels = JSONArray()
                .put(JSONObject().put("id", "routeway").put("name", "MeroMero")
                    .put("base_url", "https://api.routeway.ai/v1")
                    .put("model", "gemma-4-26b-a4b-it-meromero:free")
                    .put("free_tier", true).put("enabled", true))
                .put(JSONObject().put("id", "requesty").put("model", "legacy-requesty")
                    .put("free_tier", true).put("enabled", true))
                .put(JSONObject().put("id", "groq").put("model", "legacy-groq")
                    .put("free_tier", false).put("enabled", false))
            config.put("models", oldModels).put("current", 1)
            FileManager.write(root, "core/config.json", config.toString(2))
            FileManager.write(root, RouteMeta.PATH, JSONObject()
                .put("routeway", JSONObject().put("day_count", 1))
                .put("requesty", JSONObject().put("day_count", 2))
                .put("groq", JSONObject().put("day_count", 3)).toString(2))

            FileManager.init(root)

            val migrated = JSONObject(FileManager.read(root, "core/config.json"))
            val models = migrated.getJSONArray("models")
            assertEquals(2, models.length())
            assertTrue(models.getJSONObject(0).getString("model").contains("meromero"))
            assertEquals("minimax-m3.1-flash:free", models.getJSONObject(1).getString("model"))
            val metadata = RouteMeta.load(root)
            assertTrue(metadata.has("routeway"))
            assertFalse(metadata.has("requesty"))
            assertFalse(metadata.has("groq"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class MinimaxFreeIdTest {
    @Test fun configUsesOnlyTheExplicitFreeMiniMaxModelId() {
        val root = Files.createTempDirectory("ruri-minimax-free-id").toFile()
        try {
            FileManager.init(root)
            val models = JSONObject(FileManager.read(root, "core/config.json")).getJSONArray("models")
            val minimax = (0 until models.length()).map { models.getJSONObject(it) }
                .first { it.optString("id") == "routeway-minimax" }
            assertEquals("minimax-m3.1-flash:free", minimax.getString("model"))
            assertFalse(minimax.getString("model") == "minimax-m3.1-flash")
        } finally {
            root.deleteRecursively()
        }
    }
}

class VisionGemmaOnlyTest {
    @Test fun visionOrderContainsOnlyMeroMeroEvenWhenItsTokenBudgetIsSpent() {
        val root = Files.createTempDirectory("ruri-vision-meromero-only").toFile()
        try {
            val models = JSONArray()
                .put(JSONObject().put("id", "routeway").put("model", "gemma-4-26b-a4b-it-meromero:free")
                    .put("tokens_per_day", 400000).put("free_tier", true).put("enabled", true).put("api_key", "set"))
                .put(JSONObject().put("id", "routeway-minimax").put("model", "minimax-m3.1-flash:free")
                    .put("free_tier", true).put("enabled", true).put("api_key", "set"))
            val order = RouteSelector.orderedIndices(root, models, current = 1, task = Task.VISION, online = true, vpn = false)
            assertEquals(listOf(0), order)
        } finally {
            root.deleteRecursively()
        }
    }
}

class QuotaOrderTest {
    @Test fun exhaustedMeroMeroBudgetMovesMiniMaxToTheFrontWithoutRemovingMeroMero() {
        val root = Files.createTempDirectory("ruri-quota-order").toFile()
        try {
            FileManager.init(root)
            RouteMeta.addTokens(root, "routeway", 400000L)
            val models = JSONArray()
                .put(JSONObject().put("id", "routeway").put("model", "gemma-4-26b-a4b-it-meromero:free")
                    .put("tokens_per_day", 400000).put("free_tier", true).put("enabled", true).put("api_key", "set"))
                .put(JSONObject().put("id", "routeway-minimax").put("model", "minimax-m3.1-flash:free")
                    .put("free_tier", true).put("enabled", true).put("api_key", "set"))
            val order = RouteSelector.orderedIndices(root, models, current = 0, task = Task.CHAT, online = true, vpn = false)
            assertEquals(listOf(1, 0), order)
        } finally {
            root.deleteRecursively()
        }
    }
}

