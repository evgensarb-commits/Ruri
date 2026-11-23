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
    @Test fun migrationKeepsOnlyMeroMeroAndDolphinAndPrunesOldMetadata() {
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
                .put(JSONObject().put("id", "routeway-minimax").put("model", "minimax-m3.1-flash:free")
                    .put("free_tier", true).put("enabled", true))
                .put(JSONObject().put("id", "routeway-darksoul").put("name", "Gemma Darksoul")
                    .put("base_url", "https://api.routeway.ai/v1")
                    .put("model", "gemma-4-26b-a4b-it-darksoul:free")
                    .put("free_tier", true).put("enabled", true))
            config.remove("nsfw_only_routes_v1")
            config.put("models", oldModels).put("current", 2)
                .put("route_mode", "MANUAL")
                .put("selected_route_id", "routeway-darksoul")
            FileManager.write(root, "core/config.json", config.toString(2))
            FileManager.write(root, RouteMeta.PATH, JSONObject()
                .put("routeway", JSONObject().put("day_count", 1))
                .put("requesty", JSONObject().put("day_count", 2))
                .put("groq", JSONObject().put("day_count", 3))
                .put("routeway-minimax", JSONObject().put("day_count", 4))
                .put("routeway-darksoul", JSONObject().put("day_count", 5)).toString(2))

            FileManager.init(root)

            val migrated = JSONObject(FileManager.read(root, "core/config.json"))
            val models = migrated.getJSONArray("models")
            assertEquals(2, models.length())
            assertTrue(models.getJSONObject(0).getString("model").contains("meromero"))
            assertEquals("cognitivecomputations/dolphin3.0-mistral-24b:free", models.getJSONObject(1).getString("model"))
            assertEquals(1, models.getJSONObject(0).getInt("priority"))
            assertTrue(models.getJSONObject(0).getBoolean("nsfw"))
            assertTrue(models.getJSONObject(0).getBoolean("vision"))
            assertEquals(2, models.getJSONObject(1).getInt("priority"))
            assertTrue(models.getJSONObject(1).getBoolean("nsfw"))
            assertFalse(models.getJSONObject(1).getBoolean("vision"))
            assertEquals("openrouter-dolphin", models.getJSONObject(1).getString("id"))
            assertEquals(50, models.getJSONObject(1).getInt("rpd"))
            assertTrue(migrated.getBoolean("nsfw_override"))
            assertEquals("AUTO", migrated.getString("route_mode"))
            assertEquals("routeway", migrated.getString("selected_route_id"))
            assertEquals(0, migrated.getInt("current"))
            val metadata = RouteMeta.load(root)
            assertTrue(metadata.has("routeway"))
            assertFalse(metadata.has("requesty"))
            assertFalse(metadata.has("groq"))
            assertFalse(metadata.has("routeway-minimax"))
            assertFalse(metadata.has("routeway-darksoul"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class VisionGemmaOnlyTest {
    @Test fun cachedVisionRouteNeverReturnsDolphin() {
        val root = Files.createTempDirectory("ruri-cached-vision-meromero").toFile()
        try {
            FileManager.init(root)
            RouteMeta.setCapability(root, "openrouter-dolphin", "vision", true)
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
                .put(JSONObject().put("id", "openrouter-dolphin").put("model", "cognitivecomputations/dolphin3.0-mistral-24b:free")
                    .put("free_tier", true).put("enabled", true).put("api_key", "set").put("nsfw", true).put("vision", false))
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
                .put(JSONObject().put("id", "openrouter-dolphin").put("model", "cognitivecomputations/dolphin3.0-mistral-24b:free")
                    .put("free_tier", true).put("enabled", true).put("api_key", "set").put("priority", 2).put("nsfw", true).put("vision", false))
            val order = RouteSelector.orderedIndices(root, models, current = 0, task = Task.CHAT, online = true, vpn = false)
            assertEquals(listOf(0, 1), order)
        } finally {
            root.deleteRecursively()
        }
    }
}


class NsfwDolphinPoolTest {
    private fun configured(root: java.io.File): JSONArray {
        FileManager.init(root)
        return JSONObject(FileManager.read(root, "core/config.json")).getJSONArray("models")
    }

    @Test
    fun onlyMeroMeroAndDolphinFreeRemainConfigured() {
        val root = Files.createTempDirectory("ruri-nsfw-only-routes").toFile()
        try {
            val models = configured(root)
            assertEquals(2, models.length())
            assertEquals("routeway", models.getJSONObject(0).getString("id"))
            assertEquals("gemma-4-26b-a4b-it-meromero:free", models.getJSONObject(0).getString("model"))
            assertEquals("openrouter-dolphin", models.getJSONObject(1).getString("id"))
            assertEquals("cognitivecomputations/dolphin3.0-mistral-24b:free", models.getJSONObject(1).getString("model"))
            assertTrue((0 until models.length()).all { models.getJSONObject(it).getBoolean("nsfw") })
            assertFalse(models.toString().contains("minimax"))
            assertFalse(models.toString().contains("groq"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nsfwOverrideKeepsBothRoutesInConfiguredPriorityOrder() {
        val root = Files.createTempDirectory("ruri-nsfw-override").toFile()
        try {
            val models = configured(root)
            val order = RouteSelector.orderedIndices(root, models, 0, Task.CHAT, online = true, vpn = false, adult = true)
            assertEquals(listOf(0, 1), order)
            assertTrue(models.getJSONObject(order[0]).getBoolean("nsfw"))
            assertTrue(models.getJSONObject(order[1]).getBoolean("nsfw"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun visionRouteIsMeroMeroOnly() {
        val root = Files.createTempDirectory("ruri-nsfw-vision").toFile()
        try {
            val models = configured(root)
            val order = RouteSelector.orderedIndices(root, models, 0, Task.VISION, online = true, vpn = false, adult = true)
            assertEquals(listOf(0), order)
            assertTrue(models.getJSONObject(order.single()).getBoolean("vision"))
            assertFalse(models.getJSONObject(1).getBoolean("vision"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun real429IsClassifiedAndDolphinRemainsNextFallbackWithoutPreflightFiltering() {
        val root = Files.createTempDirectory("ruri-nsfw-real-fallback").toFile()
        try {
            val models = configured(root)
            RouteMeta.setNeedsVpn(root, "openrouter-dolphin", true)
            RouteMeta.update(root, "openrouter-dolphin") { it.put("ru_reachable", false) }
            RouteMeta.addTokens(root, "routeway", 400000L)
            assertEquals("rate_limit", RouteSelector.classify(429, null, "Too many requests"))
            assertEquals(
                listOf(0, 1),
                RouteSelector.orderedIndices(root, models, 0, Task.CHAT, online = true, vpn = false, adult = true)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun openRouterKeyIsRedactedFromWebRuntimeConfig() {
        val raw = JSONObject()
            .put("models", JSONArray().put(JSONObject()
                .put("id", "openrouter-dolphin")
                .put("api_key", "sk-or-secret")
            )).toString()
        val public = JSONObject(SecretStore.redactRuntimeConfigForWeb(raw))
        val dolphin = public.getJSONArray("models").getJSONObject(0)
        assertEquals("", dolphin.getString("api_key"))
        assertTrue(dolphin.getBoolean("has_key"))
        assertFalse(public.toString().contains("sk-or-secret"))
    }
}
