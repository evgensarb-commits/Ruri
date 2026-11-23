package com.ruri

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
                    .put("lifetime_requests", 9L)
                    .put("successes_today", 7)
                    .put("failures_today", 5)
                    .put("rate_limits_today", 2)
                    .put("network_errors_today", 1)
                    .put("timeouts_today", 1)
                    .put("http_responses_today", 12)
                    .put("http_4xx_today", 2)
                    .put("http_5xx_today", 1)
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
            val totals = snapshot.getJSONObject("model_requests")
            assertEquals(12, totals.optInt("today_total"))
            assertEquals(12L, totals.optLong("lifetime_total"))
            assertEquals(2, totals.optInt("rate_limits_429_today"))
            assertEquals(1, totals.optInt("network_errors_today"))
            assertFalse(output.contains("nsfw_capable"))
            assertFalse(output.contains("nsfw_probe_status"))
            assertFalse(output.contains("must-never-appear"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class NoDeadRoutesInDiagnosticsTest {
    @Test fun diagnosticsExposeOnlyTheModelsPresentInRuntimeConfig() {
        val root = Files.createTempDirectory("ruri-no-dead-routes-diagnostics").toFile()
        try {
            FileManager.init(root)
            val runtimeConfig = SecretStore.readRuntimeConfig(root)
            val snapshot = JSONObject(
                Diagnostics.collect(root, runtimeConfig, online = true, vpn = false)
            )
            val routes = snapshot.getJSONObject("routes")
            assertTrue(routes.length() == 2)
            assertTrue(routes.has("routeway"))
            assertTrue(routes.has("nemotron"))
            assertFalse(routes.has("routeway-minimax"))
            assertFalse(routes.has("routeway-darksoul"))
            assertFalse(routes.has("requesty"))
            assertFalse(routes.has("groq"))
            assertFalse(routes.has("gemini"))
            assertFalse(routes.has("kilo"))
        } finally {
            root.deleteRecursively()
        }
    }
}

