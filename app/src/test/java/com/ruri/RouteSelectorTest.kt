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
            assertEquals(listOf(0, 1), fallbackScores)
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
                .put(JSONObject().put("id", "routeway-darksoul").put("name", "Gemma Darksoul")
                    .put("base_url", "https://api.routeway.ai/v1")
                    .put("model", "gemma-4-26b-a4b-it-darksoul:free")
                    .put("free_tier", true).put("enabled", true))
            config.put("models", oldModels).put("current", 2)
                .put("route_mode", "MANUAL")
                .put("selected_route_id", "routeway-darksoul")
                .put("routerapi_migration_v2", false)
            FileManager.write(root, "core/config.json", config.toString(2))
            FileManager.write(root, RouteMeta.PATH, JSONObject()
                .put("routeway", JSONObject().put("day_count", 1))
                .put("requesty", JSONObject().put("day_count", 2))
                .put("groq", JSONObject().put("day_count", 3))
                .put("routeway-darksoul", JSONObject().put("day_count", 4)).toString(2))

            FileManager.init(root)

            val migrated = JSONObject(FileManager.read(root, "core/config.json"))
            val models = migrated.getJSONArray("models")
            assertEquals(5, models.length())
            assertTrue(models.getJSONObject(0).getString("model").contains("meromero"))
            assertEquals("routeway", models.getJSONObject(0).getString("id"))
            assertEquals(1, models.getJSONObject(0).getInt("priority"))
            assertTrue(models.getJSONObject(0).getBoolean("nsfw"))
            assertTrue(models.getJSONObject(0).getBoolean("vision"))
            assertEquals("routerapi-glm", models.getJSONObject(1).getString("id"))
            assertEquals("routerapi-cydonia", models.getJSONObject(2).getString("id"))
            assertEquals("routerapi-unslop", models.getJSONObject(3).getString("id"))
            assertEquals("routerapi-deepseek", models.getJSONObject(4).getString("id"))
            assertTrue(models.getJSONObject(1).getBoolean("enabled"))
            assertFalse(models.getJSONObject(1).getBoolean("free_tier"))
            assertTrue(migrated.getBoolean("nsfw_override"))
            assertEquals("routeway", migrated.getString("selected_route_id"))
            assertEquals(0, migrated.getInt("current"))
            assertEquals("routerapi-cydonia", migrated.getJSONObject("route_tasks").getJSONArray("thought").getString(1))
            val metadata = RouteMeta.load(root)
            assertTrue(metadata.has("routeway"))
            assertFalse(metadata.has("requesty"))
            assertFalse(metadata.has("groq"))
            assertFalse(metadata.has("routeway-darksoul"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class RouterApiMigrationTest {
    @Test fun migrationAddsFourRouterApiRoutesWithoutEmbeddingCredentials() {
        val root = Files.createTempDirectory("ruri-routerapi-migration").toFile()
        try {
            FileManager.init(root)
            val config = JSONObject(FileManager.read(root, "core/config.json"))
            val models = config.getJSONArray("models")
            assertEquals(5, models.length())
            val routerModels = (0 until models.length()).map { models.getJSONObject(it) }
                .filter { it.optString("id").startsWith("routerapi-") }
            assertEquals(4, routerModels.size)
            assertTrue(routerModels.all { it.optBoolean("enabled") && it.optBoolean("api_key_optional") && it.optString("api_key").isBlank() })
            assertTrue(routerModels.all { !it.optBoolean("free_tier") })
            assertEquals("https://routerai.ru/api/v1", routerModels.first().getString("base_url"))
            assertTrue(config.getBoolean("routerapi_migration_v2"))
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
            configured.getJSONObject(1).put("vision", false)
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
    @Test fun exhaustedMeroMeroBudgetDoesNotChangeManualPriorityOrder() {
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
            assertEquals(listOf(0, 1), order)
        } finally {
            root.deleteRecursively()
        }
    }
}

class TaskRouteSequenceTest {
    @Test fun codeTaskUsesConfiguredRouterApiOrderAndIncludesNonFreeRouterApiModels() {
        val root = Files.createTempDirectory("ruri-task-route-code").toFile()
        try {
            FileManager.init(root)
            val config = JSONObject(FileManager.read(root, "core/config.json"))
            val models = config.getJSONArray("models")
            for (index in 0 until models.length()) {
                val model = models.getJSONObject(index)
                if (model.optString("id").startsWith("routerapi-")) {
                    model.put("enabled", model.optString("id") == "routerapi-glm" || model.optString("id") == "routerapi-deepseek")
                }
            }
            val order = RouteSelector.orderedIndices(
                root, models, 0, Task.CODE, online = true, vpn = false, routeTaskKey = "code"
            )
            assertEquals(listOf("routerapi-glm", "routerapi-deepseek", "routeway"),
                order.map { models.getJSONObject(it).getString("id") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun sixTaskSequencesAreSavedOnlyForKnownRouteIds() {
        val config = JSONObject().put("models", JSONArray()
            .put(JSONObject().put("id", "routeway"))
            .put(JSONObject().put("id", "routerapi-glm")))
        RouteSelector.applyRouteTasks(config, JSONObject().put("route_tasks",
            JSONObject().put("code", JSONArray().put("routerapi-glm").put("unknown").put("routeway"))))
        val saved = config.getJSONObject("route_tasks").getJSONArray("code")
        assertEquals(2, saved.length())
        assertEquals("routerapi-glm", saved.getString(0))
        assertEquals("routeway", saved.getString(1))
    }
}
