package com.ruri

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSelectorTest {
    @Test
    fun manualPriorityDefinesFallbackOrderWithoutPaidRoutes() {
        val root = Files.createTempDirectory("ruri-route-selector").toFile()
        try {
            val models = JSONArray()
                .put(JSONObject().put("id", "route-a").put("free_tier", true).put("enabled", true)
                    .put("api_key", "configured").put("priority", 1))
                .put(JSONObject().put("id", "route-b").put("free_tier", true).put("enabled", true)
                    .put("api_key", "configured").put("priority", 2))
                .put(JSONObject().put("id", "paid").put("free_tier", false).put("enabled", true)
                    .put("api_key", "configured").put("priority", 3))

            RouteMeta.update(root, "route-a") { it.getJSONObject("scores").put(Task.WARMTH.name, 0.1) }
            RouteMeta.update(root, "route-b") { it.getJSONObject("scores").put(Task.WARMTH.name, 0.9) }

            val order = RouteSelector.orderedIndices(
                root, models, current = 0, task = Task.WARMTH, online = true, vpn = false
            )
            assertEquals(listOf(0, 1, 2), order)
            val fallbackScores = RouteSelector.orderedIndices(
                root, models, current = -1, task = Task.WARMTH, online = true, vpn = false
            )
            assertEquals(listOf(0, 1, 2), fallbackScores)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun enabledPaidRoutesAreEligibleSoManualModeCanIsolateOnePaidModel() {
        val root = Files.createTempDirectory("ruri-route-selector-paid").toFile()
        try {
            FileManager.init(root)
            // Commander's exact scenario: Routeway tokens are exhausted for the day, so the
            // paid Cydonia route is enabled and MANUAL mode must reach it — not silently
            // collapse to an empty order because `free_tier` was treated as a gate.
            val models = JSONArray()
                .put(JSONObject().put("id", "routeway").put("free_tier", true).put("enabled", false)
                    .put("api_key", "configured").put("priority", 1))
                .put(JSONObject().put("id", "routerapi-cydonia").put("free_tier", false)
                    .put("enabled", true).put("api_key", "configured").put("priority", 2))

            val config = JSONObject(FileManager.read(root, "core/config.json"))
                .put("route_mode", "MANUAL").put("selected_route_id", "routerapi-cydonia")
            FileManager.write(root, "core/config.json", config.toString(2))

            val order = RouteSelector.orderedIndices(
                root, models, current = -1, task = Task.CHAT, online = true, vpn = false
            )
            assertEquals(listOf(1), order)
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
    @Test fun migrationDropsMinimaxAndLegacyDeadRoutesAndKeepsConfiguredOnes() {
        val root = Files.createTempDirectory("ruri-only-two-routes").toFile()
        try {
            FileManager.init(root)
            val config = JSONObject(FileManager.read(root, "core/config.json"))
            val oldModels = JSONArray()
                .put(JSONObject().put("id", "routeway").put("name", "MeroMero")
                    .put("base_url", "https://api.routeway.ai/v1")
                    .put("model", "gemma-4-26b-a4b-it-meromero:free")
                    .put("free_tier", true).put("enabled", true).put("priority", 1))
                .put(JSONObject().put("id", "routeway-minimax").put("model", "minimax-m3.1-flash:free")
                    .put("free_tier", true).put("enabled", true).put("priority", 2))
                .put(JSONObject().put("id", "requesty").put("model", "legacy-requesty")
                    .put("free_tier", true).put("enabled", true))
                .put(JSONObject().put("id", "groq").put("model", "legacy-groq")
                    .put("free_tier", false).put("enabled", false))
            config.put("models", oldModels).put("current", 2)
            FileManager.write(root, "core/config.json", config.toString(2))

            FileManager.init(root)

            val migrated = JSONObject(FileManager.read(root, "core/config.json"))
            val models = migrated.getJSONArray("models")
            val ids = (0 until models.length()).map { models.getJSONObject(it).optString("id") }
            assertEquals(listOf("routeway"), ids)
            assertTrue(models.getJSONObject(0).getString("model").contains("meromero"))
            assertTrue(migrated.getBoolean("routes_v3_no_minimax"))
            assertFalse(ids.contains("routeway-minimax"))
            assertFalse(ids.contains("requesty"))
            assertFalse(ids.contains("groq"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun migrationKeepsUserDisabledAndCustomRoutesUntouched() {
        val root = Files.createTempDirectory("ruri-custom-routes").toFile()
        try {
            FileManager.init(root)
            val config = JSONObject(FileManager.read(root, "core/config.json"))
            val oldModels = JSONArray()
                .put(JSONObject().put("id", "routeway").put("model", "gemma-4-26b-a4b-it-meromero:free")
                    .put("free_tier", true).put("enabled", false).put("priority", 1))
                .put(JSONObject().put("id", "routerapi-glm").put("model", "z-ai/glm-5.3-flash")
                    .put("free_tier", true).put("enabled", true).put("priority", 2))
            config.put("models", oldModels)
            FileManager.write(root, "core/config.json", config.toString(2))

            FileManager.init(root)

            val migrated = JSONObject(FileManager.read(root, "core/config.json"))
            val models = migrated.getJSONArray("models")
            assertEquals(2, models.length())
            // The Commander disabled MeroMero on purpose: migration must not re-enable it.
            assertFalse(models.getJSONObject(0).getBoolean("enabled"))
            assertEquals("routerapi-glm", models.getJSONObject(1).optString("id"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class NoMinimaxAfterMigrationTest {
    @Test fun freshConfigHasNoMinimaxRoute() {
        val root = Files.createTempDirectory("ruri-no-minimax").toFile()
        try {
            FileManager.init(root)
            val models = JSONObject(FileManager.read(root, "core/config.json")).getJSONArray("models")
            val ids = (0 until models.length()).map { models.getJSONObject(it).optString("id") }
            assertFalse(ids.contains("routeway-minimax"))
            assertTrue(ids.contains("routeway"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class VisionGemmaOnlyTest {
    @Test fun cachedVisionRouteNeverReturnsMiniMax() {
        val root = Files.createTempDirectory("ruri-cached-vision-meromero").toFile()
        try {
            FileManager.init(root)
            RouteMeta.setCapability(root, "routeway-minimax", "vision", true)
            RouteMeta.setCapability(root, "routeway", "vision", false)
            val config = JSONObject(FileManager.read(root, "core/config.json"))
            val configured = config.getJSONArray("models")
            configured.getJSONObject(0).put("vision", false)
            FileManager.write(root, "core/config.json", config.toString(2))
            assertEquals(null, Vision.cachedVisionRoute(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun visionOrderContainsOnlyMeroMeroEvenWhenItsTokenBudgetIsSpent() {
        val root = Files.createTempDirectory("ruri-vision-meromero-only").toFile()
        try {
            val models = JSONArray()
                .put(JSONObject().put("id", "routeway").put("model", "gemma-4-26b-a4b-it-meromero:free")
                    .put("tokens_per_day", 400000).put("vision", true).put("free_tier", true).put("enabled", true).put("api_key", "set"))
            val order = RouteSelector.orderedIndices(root, models, current = 0, task = Task.VISION, online = true, vpn = false)
            assertEquals(listOf(0), order)
        } finally {
            root.deleteRecursively()
        }
    }
}

class QuotaOrderTest {
    @Test fun exhaustedMeroMeroBudgetDoesNotChangeManualPriorityOrder() {
        val root = Files.createTempDirectory("ruri-quota-order").toFile()
        try {
            FileManager.init(root)
            RouteMeta.addTokens(root, "routeway", 400000L)
            val models = JSONArray()
                .put(JSONObject().put("id", "routeway").put("model", "gemma-4-26b-a4b-it-meromero:free")
                    .put("tokens_per_day", 400000).put("free_tier", true).put("enabled", true).put("api_key", "set"))
            val order = RouteSelector.orderedIndices(root, models, current = 0, task = Task.CHAT, online = true, vpn = false)
            assertEquals(listOf(0), order)
        } finally {
            root.deleteRecursively()
        }
    }
}
