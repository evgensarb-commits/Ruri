package com.ruri

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads repository sources so the tests can assert wiring, not only behaviour. */
object RepoSources {
    private fun candidate(relative: String): File? {
        val roots = listOf("", "app/", "../", "../app/")
        for (root in roots) {
            val file = File(root + relative)
            if (file.isFile) return file
        }
        return null
    }

    fun text(relative: String): String {
        val file = candidate(relative) ?: return ""
        return file.readText(Charsets.UTF_8)
    }
}

class RouteCanonicalConfigTest {
    private fun root(): File {
        val dir = Files.createTempDirectory("ruri-route-canonical").toFile()
        FileManager.init(dir)
        return dir
    }

    @Test fun ensureKeepsExactlyOneMeroMeroRoute() {
        val dir = root()
        try {
            Route.ensure(dir)
            val config = JSONObject(SecretStore.readRuntimeConfig(dir))
            val models = config.getJSONArray("models")
            assertEquals(1, models.length())
            val model = models.getJSONObject(0)
            assertEquals(Route.ID, model.optString("id"))
            assertEquals(Route.BASE_URL, model.optString("base_url"))
            assertEquals(Route.MODEL, model.optString("model"))
            assertTrue(model.optBoolean("needs_vpn"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun ensureDropsRetiredFeatureKeys() {
        val dir = root()
        try {
            val polluted = JSONObject(SecretStore.readRuntimeConfig(dir))
                .put("route_tasks", JSONObject().put("chat", JSONArray().put("routeway")))
                .put("nsfw_override", true)
                .put("free_only", true)
                .put("routerapi_migration_v2", true)
                .put("selected_route_id", "routerapi-cydonia")
                .put("route_mode", "MANUAL")
            SecretStore.saveRuntimeConfig(dir, polluted.toString(2))
            Route.ensure(dir)
            val after = JSONObject(SecretStore.readRuntimeConfig(dir))
            for (key in listOf("route_tasks", "nsfw_override", "free_only",
                "routerapi_migration_v2", "selected_route_id", "route_mode")) {
                assertFalse("retired key survived: $key", after.has(key))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun ensurePreservesTheStoredProviderKey() {
        val dir = root()
        try {
            Route.setApiKey(dir, "sk-test-key-123")
            assertEquals("sk-test-key-123", Route.apiKey(dir))
            // Re-running the canonicalisation must not lose the credential.
            Route.ensure(dir)
            assertEquals("sk-test-key-123", Route.apiKey(dir))
            assertTrue(Route.hasKey(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun ensureNeverTouchesMemoryFiles() {
        val dir = root()
        try {
            FileManager.appendEvent(dir, JSONObject().put("id", "c1").put("type", "message")
                .put("who", "commander").put("text", "не забудь меня").put("ts", 111L))
            val facts = JSONObject().put("facts", JSONArray().put(JSONObject().put("text", "важный факт")))
            FileManager.write(dir, "memory/L1_facts.json", facts.toString(2))
            FileManager.write(dir, "memory/L2_diary.json",
                JSONObject().put("entries", JSONArray().put(JSONObject().put("text", "мысль"))).toString(2))
            val l0Before = FileManager.read(dir, "memory/L0_raw.jsonl")
            val l1Before = FileManager.read(dir, "memory/L1_facts.json")
            val l2Before = FileManager.read(dir, "memory/L2_diary.json")

            Route.ensure(dir)

            assertEquals(l0Before, FileManager.read(dir, "memory/L0_raw.jsonl"))
            assertEquals(l1Before, FileManager.read(dir, "memory/L1_facts.json"))
            assertEquals(l2Before, FileManager.read(dir, "memory/L2_diary.json"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun freshConfigHasNoMigrationFlagsOrExtraRoutes() {
        val dir = root()
        try {
            val config = JSONObject(FileManager.read(dir, "core/config.json"))
            val models = config.getJSONArray("models")
            assertEquals(1, models.length())
            assertEquals(Route.ID, models.getJSONObject(0).optString("id"))
            for (key in listOf("free_only", "two_route_migration_v1", "routes_v3_no_minimax",
                "manual_route_order_v1", "routerapi_migration_v2")) {
                assertFalse("seed must not carry the flag $key", config.has(key))
            }
        } finally { dir.deleteRecursively() }
    }
}

class RetryPolicyTest {
    @Test fun transientFailuresRetryInTenSeconds() {
        for (kind in listOf("rate_limit", "server", "timeout", "network", "empty_response",
            "bad_response", "other")) {
            assertTrue("$kind must be retryable", RetryPolicy.isRetryable(kind))
            assertEquals(10_000L, RetryPolicy.autoDelayMs(kind))
        }
    }

    @Test fun terminalFailuresNeverAutoRetry() {
        for (kind in listOf("no_vpn", "quota", "auth", "no_credits", "forbidden", "region",
            "bad_request", "no_key", "offline", "config", "refusal")) {
            assertTrue("$kind must be terminal", RetryPolicy.isTerminal(kind))
            assertEquals(0L, RetryPolicy.autoDelayMs(kind))
        }
    }

    @Test fun attemptCapConvertsTransientIntoManual() {
        assertEquals(10_000L, RetryPolicy.autoDelayMs("server", 1))
        assertEquals(10_000L, RetryPolicy.autoDelayMs("server", RetryPolicy.MAX_AUTO_ATTEMPTS - 1))
        assertEquals(0L, RetryPolicy.autoDelayMs("server", RetryPolicy.MAX_AUTO_ATTEMPTS))
    }

    @Test fun explanationsNameTheConcreteProblem() {
        assertTrue(RetryPolicy.explain("no_vpn").contains("VPN"))
        assertTrue(RetryPolicy.explain("quota").contains("лимит"))
        assertTrue(RetryPolicy.explain("auth").contains("401"))
        assertTrue(RetryPolicy.explain("no_key").contains("Настройки"))
    }
}

class RouteSelectorClassifyTest {
    @Test fun distinguishesPerMinuteRateFromDailyQuota() {
        assertEquals("rate_limit", RouteSelector.classify(429, null, "Too many requests per minute"))
        assertEquals("rate_limit", RouteSelector.classify(429, null, "RPM limit reached"))
        assertEquals("quota", RouteSelector.classify(429, null, "token quota exhausted"))
        assertEquals("quota", RouteSelector.classify(429, null, "daily limit reached"))
    }

    @Test fun mapsAuthenticationAndRegionFailures() {
        assertEquals("auth", RouteSelector.classify(401, null, "invalid api key"))
        assertEquals("no_credits", RouteSelector.classify(402, null, "payment required"))
        assertEquals("region", RouteSelector.classify(403, null, "not available in your country"))
        assertEquals("forbidden", RouteSelector.classify(403, null, "forbidden"))
        assertEquals("bad_request", RouteSelector.classify(404, null, "unknown model"))
        assertEquals("server", RouteSelector.classify(503, null, "unavailable"))
    }

    @Test fun mapsTransportExceptions() {
        assertEquals("timeout", RouteSelector.classify(null, java.net.SocketTimeoutException()))
        assertEquals("network", RouteSelector.classify(null, java.net.UnknownHostException("host")))
    }
}

class TransportStageDiagnosticsTest {
    private fun root(): File = Files.createTempDirectory("ruri-transport").toFile()

    @Test fun missingKeyFailsBeforeAnyNetworkStage() {
        val result = Transport.probe("")
        val failure = result as Transport.Result.Failure
        assertEquals("no_key", failure.kind)
        // The key stage is recorded, so the log explains the real cause.
        var sawKey = false
        for (i in 0 until failure.stages.length()) {
            if (failure.stages.optJSONObject(i)?.optString("name") == "KEY") sawKey = true
        }
        assertTrue("stages must include the KEY verdict", sawKey)
    }

    @Test fun everyStageCarriesNameTimingAndDetail() {
        val result = Transport.probe("")
        val stages = result.stages
        assertTrue(stages.length() > 0)
        for (i in 0 until stages.length()) {
            val stage = stages.getJSONObject(i)
            assertTrue(stage.optString("name").isNotBlank())
            assertTrue(stage.has("ms"))
            assertTrue(stage.has("ok"))
            assertTrue(stage.has("detail"))
        }
    }

    @Test fun apiMessageExtractsProviderText() {
        assertEquals("bad key",
            Transport.apiMessage("""{"error":{"message":"bad key"}}"""))
        assertEquals("quota reached",
            Transport.apiMessage("""{"message":"quota reached"}"""))
        assertTrue(Transport.apiMessage("plain text failure").contains("plain text"))
    }
}

class FullMemoryGuardTest {
    private fun system(content: String): JSONArray = JSONArray()
        .put(JSONObject().put("role", "system").put("content", content))
        .put(JSONObject().put("role", "user").put("content", "Помнишь?"))

    @Test fun acceptsContextWithLayersAndDialogueWithoutAnyToolKeyword() {
        // The previous guard also required the word "recall"; tools are gone now, so that
        // requirement would reject every well-formed request.
        val messages = system("ТВОЯ АВТОБИОГРАФИЯ (L3) ФАКТЫ (L1) ДНЕВНИК (L2) СОБЫТИЯ (L0)")
        assertTrue(ChatEngine.hasFullMemoryContext(messages))
        assertFalse(messages.getJSONObject(0).optString("content").contains("recall"))
    }

    @Test fun rejectsContextWithoutMemoryLayers() {
        assertFalse(ChatEngine.hasFullMemoryContext(system("только текущая реплика")))
    }

    @Test fun rejectsContextWithoutDialogue() {
        val onlySystem = JSONArray().put(JSONObject().put("role", "system")
            .put("content", "L0 L1 L2 L3"))
        assertFalse(ChatEngine.hasFullMemoryContext(onlySystem))
    }

    @Test fun realPromptCarriesEveryMemoryLayer() {
        val dir = Files.createTempDirectory("ruri-prompt").toFile()
        try {
            FileManager.init(dir)
            FileManager.appendEvent(dir, JSONObject().put("id", "c1").put("type", "message")
                .put("who", "commander").put("text", "привет").put("ts", 1L))
            val messages = ChatEngine.buildMessages(dir, "c1")
            assertTrue(ChatEngine.hasFullMemoryContext(messages))
            val prompt = messages.getJSONObject(0).optString("content")
            // No tool protocol may be advertised: the executors no longer exist.
            for (removed in listOf("\"action\":\"search\"", "read_shelf", "list_shelf", "read_url")) {
                assertFalse("prompt must not advertise $removed", prompt.contains(removed))
            }
        } finally { dir.deleteRecursively() }
    }
}

class RemovedToolsRejectedTest {
    @Test fun parserRejectsToolsWithoutAnExecutor() {
        for (action in listOf("search", "read_url", "list_shelf", "read_shelf")) {
            assertNull(ChatEngine.parseAction("""{"action":"$action"}"""))
        }
    }

    @Test fun parserKeepsTheToolsThatStillExist() {
        assertEquals("recall",
            ChatEngine.parseAction("""{"action":"recall","query":"котики"}""")?.optString("action"))
        assertEquals("remember_fact",
            ChatEngine.parseAction("""{"action":"remember_fact","text":"любит чай"}""")?.optString("action"))
        assertEquals("self_check",
            ChatEngine.parseAction("""{"action":"self_check"}""")?.optString("action"))
    }

    @Test fun executeToolRejectsUnknownAction() {
        val dir = Files.createTempDirectory("ruri-tools").toFile()
        try {
            FileManager.init(dir)
            val (ok, text) = ChatEngine.executeTool(dir, "search", JSONObject())
            assertFalse(ok)
            assertTrue(text.contains("Неизвестное действие"))
        } finally { dir.deleteRecursively() }
    }
}

class QueueRetryCadenceTest {
    private fun root(): File = Files.createTempDirectory("ruri-queue-cadence").toFile()

    private fun enqueue(root: File, id: String) {
        CommanderReplyQueue.enqueue(root, id, now = 1_000L)
        CommanderReplyQueue.recordAttempt(root, id, now = 1_000L)
    }

    @Test fun transientFailureSchedulesTenSecondRetry() {
        val root = root()
        try {
            enqueue(root, "m1")
            val delay = CommanderReplyQueue.recordFailure(root, "m1", "server", now = 5_000L)
            assertEquals(10_000L, delay)
            val status = CommanderReplyQueue.status(root, now = 5_000L)
            assertEquals(15_000L, status.optLong("next_attempt_at"))
            assertFalse(status.optBoolean("manual_retry"))
            assertTrue(CommanderReplyQueue.nextDue(root, now = 14_999L) == null)
            assertNotNull(CommanderReplyQueue.nextDue(root, now = 15_000L))
        } finally { root.deleteRecursively() }
    }

    @Test fun terminalFailureWaitsForTheManualButton() {
        val root = root()
        try {
            enqueue(root, "m2")
            val delay = CommanderReplyQueue.recordFailure(root, "m2", "no_vpn", now = 5_000L)
            assertEquals(0L, delay)
            val status = CommanderReplyQueue.status(root, now = 5_000L)
            assertTrue(status.optBoolean("manual_retry"))
            assertEquals("no_vpn", status.optString("last_error_kind"))
            assertTrue(status.optString("explanation").contains("VPN"))
            // Nothing becomes due automatically, ever.
            assertNull(CommanderReplyQueue.nextDue(root, now = 999_999_999L))
            assertNull(CommanderReplyQueue.nextDelayMs(root, now = 5_000L))
        } finally { root.deleteRecursively() }
    }

    @Test fun manualRetryMakesTheHeadDueImmediately() {
        val root = root()
        try {
            enqueue(root, "m3")
            CommanderReplyQueue.recordFailure(root, "m3", "quota", now = 5_000L)
            assertNull(CommanderReplyQueue.nextDue(root, now = 5_000L))
            CommanderReplyQueue.manualRetry(root, now = 9_000L)
            val due = CommanderReplyQueue.nextDue(root, now = 9_000L)
            assertNotNull(due)
            assertEquals(0, due!!.optInt("attempts"))
            assertFalse(CommanderReplyQueue.needsManualRetry(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun statusIsEmptyWithoutPendingWork() {
        val root = root()
        try {
            val status = CommanderReplyQueue.status(root)
            assertEquals(0, status.optInt("pending"))
            assertFalse(status.optBoolean("manual_retry"))
        } finally { root.deleteRecursively() }
    }
}

class DiagnosticsVerdictTest {
    @Test fun verdictNamesVpnWhenTunnelIsMissing() {
        val verdict = Diagnostics.verdict("network", null, "timeout", true, false, "key")
        assertTrue(verdict.contains("VPN"))
    }

    @Test fun verdictNamesMissingKey() {
        val verdict = Diagnostics.verdict("no_key", null, "", true, true, "")
        assertTrue(verdict.contains("Ключ"))
    }

    @Test fun verdictNamesOffline() {
        val verdict = Diagnostics.verdict("offline", null, "", false, false, "key")
        assertTrue(verdict.contains("интернет"))
    }

    @Test fun verdictConfirmsASuccessfulProbe() {
        val verdict = Diagnostics.verdict("ok", 200, "", true, true, "key")
        assertTrue(verdict.contains("работает"))
    }

    @Test fun snapshotReportsTheSingleRouteAndNothingElse() {
        val dir = Files.createTempDirectory("ruri-diag").toFile()
        try {
            FileManager.init(dir)
            Route.setApiKey(dir, "sk-live")
            val snapshot = JSONObject(Diagnostics.snapshot(dir, online = true, vpn = true))
            val route = snapshot.getJSONObject("route")
            assertEquals(Route.ID, route.optString("id"))
            assertEquals(Route.MODEL, route.optString("model"))
            assertTrue(route.optBoolean("has_key"))
            assertTrue(snapshot.has("stages") || snapshot.has("last_attempt"))
            assertTrue(snapshot.has("log_tail"))
            assertTrue(snapshot.has("memory"))
            // No secrets may leak into the diagnostics payload.
            assertFalse(snapshot.toString().contains("sk-live"))
        } finally { dir.deleteRecursively() }
    }
}

class UiBridgeContractTest {
    private fun html(): String = RepoSources.text("app/src/main/assets/index.html")
    private fun activity(): String = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")

    @Test fun uiIsAMessengerWithoutTheRemovedFeatures() {
        val html = html()
        assertTrue(html.isNotBlank())
        for (removed in listOf("МАРШРУТЫ", "VISION", "ПОЛКА", "ROUTERAPI", "shelf", "serper", "searxng")) {
            assertFalse("UI must not mention $removed", html.contains(removed, ignoreCase = true))
        }
        // The three panes of the down-graded app.
        assertTrue(html.contains("ЧАТ"))
        assertTrue(html.contains("ПАМЯТЬ"))
        assertTrue(html.contains("НАСТРОЙКИ"))
        // No model picker: there is only one model.
        assertFalse(html.contains("activeModelSelect"))
        assertFalse(html.contains("primaryRouteSelect"))
    }

    @Test fun uiDefinesEveryNativeCallback() {
        val html = html()
        for (callback in listOf("onNativeChatResult", "onNativeChatError", "onNativeQueueState",
            "startOnboarding", "openMessageById")) {
            assertTrue("missing callback $callback", html.contains(callback))
        }
    }

    @Test fun uiOnlyCallsBridgeMethodsThatExist() {
        val html = html()
        val activity = activity()
        val calls = Regex("Ruri\\.([A-Za-z][A-Za-z0-9_]*)").findAll(html)
            .map { it.groupValues[1] }.toSet()
        assertTrue("UI must call at least one bridge method", calls.isNotEmpty())
        for (call in calls) {
            assertTrue("bridge is missing $call", activity.contains("fun $call("))
        }
    }

    @Test fun bridgeExposesRetryAndDiagnosticsContract() {
        val activity = activity()
        for (method in listOf("queueStatus", "retryPending", "runDiagnostics", "readDiagnosticsLog",
            "getDiagnostics", "appendEvent", "completeChat", "readMemoryLayer", "readLastEvents")) {
            assertTrue("bridge must expose $method", activity.contains("fun $method("))
        }
        assertFalse("vision bridge must be gone", activity.contains("fun visionStatus("))
        assertFalse("shelf bridge must be gone", activity.contains("fun saveShelfFile("))
        assertFalse("search bridge must be gone", activity.contains("fun searchWeb("))
    }

    @Test fun retryTimerIsCancelledWhenTheScreenGoesAway() {
        val activity = activity()
        // Doze / background must not keep hitting the provider.
        assertTrue(activity.contains("mainHandler.removeCallbacks(autoRetry)"))
        assertTrue(activity.contains("if (!activityResumed) return"))
    }
}

class RemovedModulesGoneTest {
    @Test fun sourceTreeHasNoRetiredFeatureFiles() {
        for (name in listOf("Vision.kt", "ShelfWatcher.kt", "WebSearch.kt", "SearchRouter.kt",
            "UrlReader.kt", "FreeModelRouter.kt", "Probe.kt", "RouterAPIMigration.kt")) {
            val file = File("app/src/main/java/com/ruri/$name")
            val alt = File("src/main/java/com/ruri/$name")
            assertFalse("$name must be deleted", file.exists() || alt.exists())
        }
    }

    @Test fun autonomousLivingIsKept() {
        // Autonomy must survive the down-grade: she still writes to chat or diary by herself.
        val thinker = RepoSources.text("app/src/main/java/com/ruri/Thinker.kt")
        assertTrue(thinker.contains("EmotionEngine.initiativeGate"))
        assertTrue(thinker.contains("publishesToChat"))
        val emotion = RepoSources.text("app/src/main/java/com/ruri/EmotionEngine.kt")
        assertTrue(emotion.contains("initiativeGate"))
        assertTrue(emotion.contains("pressure"))
    }
}
