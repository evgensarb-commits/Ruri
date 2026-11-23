package com.ruri

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMetaTest {
    @Test
    fun everyOutboundAttemptConsumesDailyQuota() {
        val root = Files.createTempDirectory("ruri-route-quota").toFile()
        try {
            assertTrue(RouteMeta.quotaLeft(root, "route-test", 2))
            RouteMeta.recordAttempt(root, "route-test")
            assertTrue(RouteMeta.quotaLeft(root, "route-test", 2))
            RouteMeta.recordAttempt(root, "route-test")
            assertTrue(RouteMeta.quotaLeft(root, "route-test", 2))
            assertEquals(2, RouteMeta.route(root, "route-test").optInt("day_count"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun taskScoreMovesUpOnSuccessAndDownOnFailure() {
        val root = Files.createTempDirectory("ruri-route-score").toFile()
        try {
            val before = RouteSelector.score(RouteMeta.route(root, "route-test"), Task.WARMTH)
            RouteMeta.recordSuccess(root, "route-test", Task.WARMTH)
            val afterSuccess = RouteSelector.score(RouteMeta.route(root, "route-test"), Task.WARMTH)
            RouteMeta.recordFailure(root, "route-test", "other", Task.WARMTH)
            val afterFailure = RouteSelector.score(RouteMeta.route(root, "route-test"), Task.WARMTH)

            assertEquals(0.5, before, 0.0001)
            assertTrue(afterSuccess > before)
            assertTrue(afterFailure < afterSuccess)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun secondConsecutiveSuccessPromotesThatTaskScore() {
        val root = Files.createTempDirectory("ruri-route-promotion").toFile()
        try {
            RouteMeta.recordSuccess(root, "route-test", Task.REASON)
            val afterOne = RouteSelector.score(RouteMeta.route(root, "route-test"), Task.REASON)
            RouteMeta.recordSuccess(root, "route-test", Task.REASON)
            val afterTwo = RouteSelector.score(RouteMeta.route(root, "route-test"), Task.REASON)

            assertTrue(afterTwo >= 0.75)
            assertTrue(afterTwo > afterOne)
        } finally {
            root.deleteRecursively()
        }
    }


    @Test
    fun cheapHeartbeatIsThrottledToTwentyMinutes() {
        val root = Files.createTempDirectory("ruri-heartbeat-rate").toFile()
        try {
            val config = org.json.JSONObject().put("models", org.json.JSONArray())
            val first = System.currentTimeMillis()
            Probe.heartbeat(root, config, first)
            assertEquals(first, RouteMeta.route(root, "_global").optLong("last_heartbeat_at"))

            Probe.heartbeat(root, config, first + 19L * 60L * 1000L)
            assertEquals(first, RouteMeta.route(root, "_global").optLong("last_heartbeat_at"))

            val next = first + Probe.HEARTBEAT_MS + 1L
            Probe.heartbeat(root, config, next)
            assertEquals(next, RouteMeta.route(root, "_global").optLong("last_heartbeat_at"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repeatedFailuresAndLegacyCooldownNeverBlockRoute() {
        val root = Files.createTempDirectory("ruri-route-cooldown").toFile()
        try {
            RouteMeta.recordFailure(root, "route-test", "server")
            assertTrue(RouteMeta.available(root, "route-test"))
            RouteMeta.recordFailure(root, "route-test", "server")
            assertTrue(RouteMeta.available(root, "route-test"))
            RouteMeta.recordFailure(root, "quota-route", "quota")
            assertTrue(RouteMeta.available(root, "quota-route"))
            RouteMeta.update(root, "legacy-route") { it.put("cooldown_until", Long.MAX_VALUE) }
            assertTrue(RouteMeta.available(root, "legacy-route"))
        } finally {
            root.deleteRecursively()
        }
    }
    @Test
    fun modelRequestCountersSeparate429FromNetworkFailures() {
        val root = Files.createTempDirectory("ruri-route-telemetry").toFile()
        try {
            RouteMeta.recordAttempt(root, "route-test")
            RouteMeta.recordHttpStatus(root, "route-test", 429)
            RouteMeta.recordFailure(root, "route-test", "rate_limit")
            RouteMeta.recordAttempt(root, "route-test")
            RouteMeta.recordHttpStatus(root, "route-test", 429)
            RouteMeta.recordFailure(root, "route-test", "quota")
            RouteMeta.recordAttempt(root, "route-test")
            RouteMeta.recordFailure(root, "route-test", "network")
            RouteMeta.recordAttempt(root, "route-test")
            RouteMeta.recordFailure(root, "route-test", "region")
            RouteMeta.recordAttempt(root, "route-test")
            RouteMeta.recordSuccess(root, "route-test")
            val route = RouteMeta.route(root, "route-test")
            assertEquals(5, route.optInt("day_count"))
            assertEquals(5L, route.optLong("lifetime_requests"))
            assertEquals(2, route.optInt("rate_limits_today"))
            assertEquals(2, route.optInt("http_responses_today"))
            assertEquals(2, route.optInt("http_4xx_today"))
            assertEquals(1, route.optInt("network_errors_today"))
            assertEquals(1, route.optInt("region_restrictions_today"))
            assertEquals(1, route.optInt("successes_today"))
            assertEquals(4, route.optInt("failures_today"))
        } finally {
            root.deleteRecursively()
        }
    }

}
