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
            assertFalse(RouteMeta.quotaLeft(root, "route-test", 2))
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
    fun twoOrdinaryFailuresApplyCooldownAndQuotaFailureBlocksUntilUtcMidnight() {
        val root = Files.createTempDirectory("ruri-route-cooldown").toFile()
        try {
            RouteMeta.recordFailure(root, "route-test", "server")
            assertTrue(RouteMeta.available(root, "route-test"))
            RouteMeta.recordFailure(root, "route-test", "server")
            assertFalse(RouteMeta.available(root, "route-test"))
            RouteMeta.recordFailure(root, "quota-route", "quota")
            assertFalse(RouteMeta.available(root, "quota-route"))
        } finally {
            root.deleteRecursively()
        }
    }
}
