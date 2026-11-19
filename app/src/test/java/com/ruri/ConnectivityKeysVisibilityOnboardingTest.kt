package com.ruri

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RouteRecoveryOnEmptyOrderTest {
    @Test fun emptyOrderCanTriggerOneForcedRefresh() {
        assertTrue(RouteRecovery.shouldRefresh(0L, 60_000L))
        assertFalse(RouteRecovery.shouldRefresh(10_000L, 69_999L))
    }
}

class ForceRefreshThrottleTest {
    @Test fun recoveryRefreshHasSixtySecondThrottle() {
        assertTrue(Probe.recoveryRefreshAllowed(1_000L, 61_000L))
        assertFalse(Probe.recoveryRefreshAllowed(1_000L, 60_999L))
    }
}

class ErrorSurfaceTest {
    @Test fun errorsNameTheRouteAndCause() {
        val quota = RouteRecovery.describeHttpError("Routeway", 429, "quota")
        assertTrue(quota.contains("Routeway"))
        assertTrue(quota.contains("квота"))
        assertTrue(RouteRecovery.describeHttpError("Routeway", 403, "region").contains("VPN"))
    }
}

class PinAndExclusiveRouteTest {
    @Test fun exclusiveModeKeepsOnlyCommanderSelectedRoute() {
        assertEquals(listOf(2), RouteRecovery.applyExclusive(listOf(0, 2, 1), 2, true))
        assertEquals(listOf(0, 2, 1), RouteRecovery.applyExclusive(listOf(0, 2, 1), 2, false))
    }
}

class KeyPersistenceAcrossMigrationTest {
    @Test fun mergingConfigDoesNotDiscardPreviouslyStoredKeys() {
        val old = JSONObject().put("routeway", "old-secret").put("requesty", "requesty-secret")
        val imported = JSONObject().put("routeway", "new-legacy-secret").put("kilo", "")
        val merged = SecretStore.mergeSecretMaps(old, imported)
        assertEquals("old-secret", merged.getString("routeway"))
        assertEquals("requesty-secret", merged.getString("requesty"))
        assertFalse(merged.has("kilo"))
        val models = FileManager.mergeModelsById(JSONArray()
            .put(JSONObject().put("id", "routeway").put("api_key", "legacy-key").put("base_url", ""))
            .put(JSONObject().put("id", "routeway").put("api_key", "").put("model", "free-model")))
        assertEquals(1, models.length())
        assertEquals("legacy-key", models.getJSONObject(0).getString("api_key"))
        assertEquals("free-model", models.getJSONObject(0).getString("model"))
    }
}

class LegacyKeyImportTest {
    @Test fun importsPerModelAndTopLevelLegacyKeys() {
        val perModel = JSONObject().put("models", JSONArray().put(JSONObject().put("id", "routeway").put("api_key", "route-key")))
        assertEquals("route-key", SecretStore.legacyKeys(perModel).getString("routeway"))
        val topLevel = JSONObject().put("current", 0).put("api_key", "legacy-top-level")
            .put("models", JSONArray().put(JSONObject().put("id", "routeway").put("api_key", "")))
        assertEquals("legacy-top-level", SecretStore.legacyKeys(topLevel).getString("routeway"))
    }
}

class CheckpointAfterReplyTest {
    @Test fun checkpointCanCaptureL4AfterReply() {
        val root = Files.createTempDirectory("ruri-reply-checkpoint").toFile()
        try {
            val state = JSONObject().put("mood", "радостная").put("onboarding_done", false).toString()
            FileManager.write(root, "memory/L4_state.json", state)
            BackupPolicy.checkpointState(root)
            assertTrue(FileManager.read(root, "backup/checkpoints/L4_state.json").contains("радостная"))
        } finally { root.deleteRecursively() }
    }
}

class BackupVisibilityTest {
    @Test fun diagnosticsExposeJournalAndSnapshotTimestamps() {
        val root = Files.createTempDirectory("ruri-backup-visibility").toFile()
        try {
            FileManager.appendEvent(root, JSONObject().put("type", "message").put("who", "commander").put("text", "test").put("ts", 123456L))
            val snapshot = JSONObject(Diagnostics.collect(root, "{}", true, false))
            assertEquals(123456L, snapshot.getJSONObject("memory").getLong("l0_last_append_at"))
            assertTrue(snapshot.getJSONObject("backups").has("latest_copy_at"))
        } finally { root.deleteRecursively() }
    }
}

class FactFieldRemovedTest {
    @Test fun summaryReplacesManualFactEntry() {
        assertEquals("Фактов в L1: 4. Извлекаются автоматически; Рури может добавить свой инструментом remember_fact.", BrainUiContract.factSummary(4))
        val htmlFile = File("src/main/assets/index.html")
        assertTrue("index.html must be available to the unit test", htmlFile.isFile)
        assertTrue(BrainUiContract.manualFactControlsRemoved(htmlFile.readText()))
    }
}

class OnboardingFlowTest {
    @Test fun permissionsAreRequestedInSequence() {
        assertEquals(OnboardingFlow.Step.POST_NOTIFICATIONS, OnboardingFlow.nextStep(false, false, false))
        assertEquals(OnboardingFlow.Step.NOTIFICATION_ACCESS, OnboardingFlow.nextStep(true, false, false))
        assertEquals(OnboardingFlow.Step.USAGE_ACCESS, OnboardingFlow.nextStep(true, true, false))
        assertEquals(OnboardingFlow.Step.COMPLETE, OnboardingFlow.nextStep(true, true, true))
    }
}
