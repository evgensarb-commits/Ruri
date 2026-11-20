package com.ruri

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test
    fun snapshotShowsSystemStateWithoutExposingProviderKeys() {
        val root = Files.createTempDirectory("ruri-diagnostics").toFile()
        try {
            FileManager.write(root, "memory/L4_state.json", JSONObject().put("pending_reply", true).toString())
            val now = System.currentTimeMillis()
            val utcDay = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(now))
            FileManager.write(root, RouteMeta.PATH, JSONObject().put(
                "routeway", JSONObject()
                    .put("reachable", false)
                    .put("needs_vpn", true)
                    .put("day_key", utcDay)
                    .put("day_count", 12)
                    .put("caps", JSONObject().put("nsfw", true))
                    .put("scores", JSONObject().put("CHAT", 0.9))
            ).toString())

            val config = JSONObject().put("models", JSONArray().put(
                JSONObject()
                    .put("id", "routeway")
                    .put("name", "Routeway")
                    .put("enabled", true)
                    .put("free_tier", true)
                    .put("api_key", "must-never-appear")
                    .put("rpd", 200)
                    .put("nsfw_capable", true)
                    .put("nsfw_probe_status", "passed")
            )).toString()

            val output = Diagnostics.collect(root, config, online = false, vpn = true, now = now)
            val snapshot = JSONObject(output)
            val route = snapshot.getJSONObject("routes").getJSONObject("routeway")

            assertFalse(snapshot.getJSONObject("network").getBoolean("online"))
            assertTrue(snapshot.getJSONObject("network").getBoolean("vpn_active"))
            assertTrue(snapshot.getBoolean("offline_pending"))
            assertTrue(snapshot.getJSONObject("memory").getLong("l0_size_bytes") == 0L)
            assertFalse(route.getBoolean("reachable"))
            assertTrue(route.getBoolean("needs_vpn"))
            assertTrue(route.getBoolean("api_key_configured"))
            assertTrue(output.contains("\"requests_today\": 12"))
            assertFalse(output.contains("nsfw_capable"))
            assertFalse(output.contains("nsfw_probe_status"))
            assertFalse(output.contains("must-never-appear"))
        } finally {
            root.deleteRecursively()
        }
    }
}
