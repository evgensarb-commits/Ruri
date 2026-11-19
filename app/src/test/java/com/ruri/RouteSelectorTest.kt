package com.ruri

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSelectorTest {
    @Test
    fun prefersHigherPerTaskScoreAndNeverSelectsPaidRoutes() {
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

            val order = RouteSelector.ordered(
                root, models, current = 0, task = Task.WARMTH, needNsfw = false,
                online = true, vpn = false
            )
            assertEquals(listOf(1, 0), order)
            assertTrue(order.none { models.getJSONObject(it).optString("id") == "paid" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun vpnDownUsesOnlyRoutesKnownToBeReachableWithoutVpn() {
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
                listOf(1),
                RouteSelector.ordered(root, models, 0, Task.CHAT, false, online = true, vpn = false)
            )
            assertEquals(
                listOf(0, 1),
                RouteSelector.ordered(root, models, 0, Task.CHAT, false, online = true, vpn = true)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun adultRouteMustHavePassedItsLiveProbeAndMetadataCapability() {
        val root = Files.createTempDirectory("ruri-nsfw-route").toFile()
        try {
            val models = JSONArray()
                .put(JSONObject().put("id", "untested").put("free_tier", true).put("enabled", true)
                    .put("api_key", "configured").put("nsfw_capable", true)
                    .put("nsfw_tested_at", 0L).put("nsfw_probe_status", "passed"))
                .put(JSONObject().put("id", "passed").put("free_tier", true).put("enabled", true)
                    .put("api_key", "configured").put("nsfw_capable", true)
                    .put("nsfw_tested_at", 100L).put("nsfw_probe_status", "passed"))
            RouteMeta.setCapability(root, "passed", "nsfw", true)

            assertEquals(
                listOf(1),
                RouteSelector.ordered(root, models, 0, Task.CREATIVE, true, online = true, vpn = false)
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
