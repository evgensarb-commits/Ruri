package com.ruri

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualPriorityOrderTest {
    private fun models(): JSONArray = JSONArray()
        .put(JSONObject().put("id", "routeway").put("model", "gemma-meromero:free")
            .put("free_tier", true).put("enabled", true).put("api_key", "set")
            .put("priority", 2).put("nsfw", true).put("vision", true))
        .put(JSONObject().put("id", "routeway-minimax").put("model", "minimax-m3.1-flash:free")
            .put("free_tier", true).put("enabled", true).put("api_key", "set")
            .put("priority", 1).put("nsfw", false).put("vision", false))

    private fun root(override: Boolean = true) = Files.createTempDirectory("ruri-manual-routes").toFile().also {
        FileManager.init(it)
        val config = JSONObject(FileManager.read(it, "core/config.json"))
            .put("nsfw_override", override)
        FileManager.write(it, "core/config.json", config.toString(2))
    }

    @Test
    fun manualPriorityNumbersDefineNormalOrder() {
        val root = root()
        try {
            assertEquals(listOf(1, 0), RouteSelector.orderedIndices(root, models(), 0, Task.CHAT))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun fallbackIsTheNextEnabledRouteInManualOrder() {
        val root = root()
        try {
            val order = RouteSelector.orderedIndices(root, models(), 0, Task.CHAT)
            assertEquals(1, order[0])
            assertEquals(0, order[1])
        } finally { root.deleteRecursively() }
    }

    @Test
    fun nsfwOverrideMovesDeclaredNsfwRouteAheadForAdultTopic() {
        val root = root(true)
        try {
            assertEquals(listOf(0, 1), RouteSelector.orderedIndices(
                root, models(), 1, Task.CHAT, adult = true
            ))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun nsfwOverrideOffPreservesManualOrder() {
        val root = root(false)
        try {
            assertEquals(listOf(1, 0), RouteSelector.orderedIndices(
                root, models(), 0, Task.CHAT, adult = true
            ))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun visionUsesOnlyManuallyDeclaredVisionRoutes() {
        val root = root()
        try {
            assertEquals(listOf(0), RouteSelector.orderedIndices(root, models(), 1, Task.VISION))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun quotaUsageDoesNotReorderManualPriorities() {
        val root = root()
        try {
            RouteMeta.addTokens(root, "routeway", 400000L)
            assertEquals(listOf(1, 0), RouteSelector.orderedIndices(root, models(), 0, Task.CHAT))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun saveRouteOrderUpdatesNumbersAndManualCapabilityFlags() {
        val config = JSONObject().put("models", models())
        val payload = JSONObject().put("nsfw_override", false).put("routes", JSONArray()
            .put(JSONObject().put("id", "routeway").put("priority", 7).put("nsfw", false).put("vision", false))
            .put(JSONObject().put("id", "routeway-minimax").put("priority", 1).put("nsfw", true).put("vision", true)))
        RouteSelector.applyManualRouteOrder(config, payload)
        val updated = config.getJSONArray("models")
        assertEquals(7, updated.getJSONObject(0).getInt("priority"))
        assertFalse(updated.getJSONObject(0).getBoolean("nsfw"))
        assertFalse(updated.getJSONObject(0).getBoolean("vision"))
        assertEquals(1, updated.getJSONObject(1).getInt("priority"))
        assertTrue(updated.getJSONObject(1).getBoolean("nsfw"))
        assertTrue(updated.getJSONObject(1).getBoolean("vision"))
        assertFalse(config.getBoolean("nsfw_override"))
    }
    @Test
    fun autoUsesManualPriorityOrderRegardlessOfSelectedModel() {
        val root = root()
        try {
            val config = JSONObject(FileManager.read(root, "core/config.json"))
                .put("route_mode", "AUTO").put("selected_route_id", "routeway")
            FileManager.write(root, "core/config.json", config.toString(2))
            assertEquals(listOf(1, 0), RouteSelector.orderedIndices(root, models(), 0, Task.CHAT))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun manualPinsAllRequestsToSelectedModelWithoutFallback() {
        val root = root()
        try {
            val config = JSONObject(FileManager.read(root, "core/config.json"))
                .put("route_mode", "MANUAL").put("selected_route_id", "routeway")
            FileManager.write(root, "core/config.json", config.toString(2))
            assertEquals(listOf(0), RouteSelector.orderedIndices(root, models(), 1, Task.CHAT))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun manualFallbackStartsWithSelectedThenUsesPriorityOrder() {
        val root = root()
        try {
            val config = JSONObject(FileManager.read(root, "core/config.json"))
                .put("route_mode", "MANUAL_FALLBACK").put("selected_route_id", "routeway")
            FileManager.write(root, "core/config.json", config.toString(2))
            assertEquals(listOf(0, 1), RouteSelector.orderedIndices(root, models(), 1, Task.CHAT))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun manualVisionRequiresSelectedRouteToDeclareVision() {
        val root = root()
        try {
            val config = JSONObject(FileManager.read(root, "core/config.json"))
                .put("route_mode", "MANUAL").put("selected_route_id", "routeway-minimax")
            FileManager.write(root, "core/config.json", config.toString(2))
            assertEquals(emptyList<Int>(), RouteSelector.orderedIndices(root, models(), 1, Task.VISION))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun savingRouteModeAndSelectedRoutePersistsBoth() {
        val config = JSONObject().put("models", models())
        val payload = JSONObject().put("route_mode", "MANUAL_FALLBACK")
            .put("selected_route_id", "routeway").put("routes", JSONArray())
        RouteSelector.applyManualRouteOrder(config, payload)
        assertEquals("MANUAL_FALLBACK", config.getString("route_mode"))
        assertEquals("routeway", config.getString("selected_route_id"))
        assertEquals(0, config.getInt("current"))
    }

}
