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
            assertEquals(Route.ID_ROUTEWAY, model.optString("id"))
            assertEquals(Route.ROUTEWAY_BASE_URL, model.optString("base_url"))
            assertEquals(Route.MODEL_FREE, model.optString("model"))
            assertTrue(model.optBoolean("needs_vpn"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun ensureDropsRetiredFeatureKeys() {
        val dir = root()
        try {
            // The public config is written directly: the JVM unit tests have no
            // AndroidKeyStore, and the cleanup under test never needs a real secret.
            val polluted = JSONObject(FileManager.read(dir, "core/config.json"))
                .put("route_tasks", JSONObject().put("chat", JSONArray().put("routeway")))
                .put("nsfw_override", true)
                .put("free_only", true)
                .put("routerapi_migration_v2", true)
                .put("selected_route_id", "routerapi-cydonia")
                .put("route_mode", "MANUAL")
            FileManager.write(dir, "core/config.json", polluted.toString(2))
            Route.ensure(dir)
            val after = JSONObject(FileManager.read(dir, "core/config.json"))
            for (key in listOf("route_tasks", "nsfw_override", "free_only",
                "routerapi_migration_v2", "selected_route_id", "route_mode")) {
                assertFalse("retired key survived: $key", after.has(key))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun canonicalRouteCarriesWhateverKeyItIsGiven() {
        val withKey = Route.canonical("sk-test-key-123")
        assertEquals("sk-test-key-123", withKey.optString("api_key"))
        assertEquals(Route.ROUTEWAY_BASE_URL, withKey.optString("base_url"))
        assertEquals(Route.MODEL_FREE, withKey.optString("model"))
        assertTrue(withKey.optBoolean("needs_vpn"))
        assertEquals("", Route.canonical().optString("api_key"))
    }

    @Test fun savingAKeyNeverLeavesPlaintextOnDisk() {
        val dir = root()
        try {
            // On the JVM the encrypted store is unavailable, so this exercises the
            // degraded path: the config must stay canonical and must never hold the key
            // in clear text.
            Route.setApiKey(dir, "sk-must-not-leak")
            val onDisk = FileManager.read(dir, "core/config.json")
            assertFalse("plaintext key leaked to config.json", onDisk.contains("sk-must-not-leak"))
            val model = JSONObject(onDisk).getJSONArray("models").getJSONObject(0)
            assertEquals(Route.ID_ROUTEWAY, model.optString("id"))
            assertEquals("", model.optString("api_key"))
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
            assertEquals(Route.ID_ROUTEWAY, models.getJSONObject(0).optString("id"))
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
            // Removed organs must stay removed, and a tool that was not offered on this turn must
            // not be named either. read_url is advertised only when the Commander pastes a link.
            for (removed in listOf("\"action\":\"search\"", "read_shelf", "list_shelf")) {
                assertFalse("prompt must not advertise $removed", prompt.contains(removed))
            }
            assertFalse("an unoffered tool must not be named", prompt.contains("read_url"))
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
            val snapshot = JSONObject(Diagnostics.snapshot(dir, online = true, vpn = true))
            val route = snapshot.getJSONObject("route")
            assertEquals(Route.ID_ROUTEWAY, route.optString("id"))
            assertEquals(Route.MODEL_FREE, route.optString("model"))
            assertEquals(Route.ROUTEWAY_BASE_URL, route.optString("base_url"))
            assertTrue(route.optBoolean("needs_vpn"))
            assertTrue(snapshot.has("queue"))
            assertTrue(snapshot.has("log_tail"))
            assertTrue(snapshot.has("memory"))
            // The payload must never carry a provider key.
            assertFalse(snapshot.toString().contains("api_key"))
        } finally { dir.deleteRecursively() }
    }
}

class UiBridgeContractTest {
    private fun html(): String = RepoSources.text("app/src/main/assets/index.html")
    private fun activity(): String = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")

    @Test fun uiIsAMessengerWithoutTheRemovedFeatures() {
        val html = html()
        assertTrue(html.isNotBlank())
        // Vision is a live capability again, so it is no longer in this list.
        for (removed in listOf("МАРШРУТЫ", "ПОЛКА", "ROUTERAPI", "shelf", "serper", "searxng")) {
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
        // The UI reaches the bridge through the call() helper, so the contract test must
        // read that form too — otherwise a renamed method slips through and only fails on a
        // device, where nothing catches it.
        val viaHelper = Regex("call\\(\\s*[\"']([A-Za-z][A-Za-z0-9_]*)[\"']")
            .findAll(html).map { it.groupValues[1] }.toSet()
        val direct = Regex("Ruri\\.([A-Za-z][A-Za-z0-9_]*)")
            .findAll(html).map { it.groupValues[1] }.toSet()
        val calls = viaHelper + direct
        assertTrue("UI must call at least one bridge method", calls.isNotEmpty())
        assertTrue("UI must use the call() helper", viaHelper.isNotEmpty())
        for (call in calls) {
            assertTrue("bridge is missing $call", activity.contains("fun $call("))
        }
    }

    @Test fun identityFilesAreWritableAndMemoryIsNot() {
        val activity = activity()
        // Her self-description may be rewritten; episodic memory may not.
        assertTrue(activity.contains("core/character.json"))
        assertTrue(activity.contains("core/appearance.json"))
        for (forbidden in listOf("memory/L0_raw.jsonl", "memory/L1_facts.json", "memory/L2_diary.json")) {
            assertFalse(
                "the generic bridge must never accept $forbidden",
                Regex("selfDescribingPaths[^\\n]*" + Regex.escape(forbidden)).containsMatchIn(activity)
            )
        }
        assertTrue(activity.contains("self_description_changed"))
    }

    @Test fun bridgeExposesRetryAndDiagnosticsContract() {
        val activity = activity()
        for (method in listOf("queueStatus", "retryPending", "runDiagnostics", "readDiagnosticsLog",
            "getDiagnostics", "appendEvent", "completeChat", "readMemoryLayer", "readLastEvents")) {
            assertTrue("bridge must expose $method", activity.contains("fun $method("))
        }
        // Vision is back, but it must report a real capability state, not a config flag.
        assertTrue(activity.contains("fun visionStatus("))
        assertTrue(activity.contains("fun checkVisionSupport("))
        assertFalse("shelf bridge must be gone", activity.contains("fun saveShelfFile("))
        assertFalse("search bridge must be gone", activity.contains("fun searchWeb("))
    }

    @Test fun theScreenNeverStopsHerWork() {
        val activity = activity()
        // The Commander's rule: she works always, screen or no screen. The old behaviour refused
        // to retry while the app was backgrounded, which is exactly what was forbidden.
        assertTrue("the in-process timer is still cancelled on pause to avoid a duplicate wake",
            activity.contains("mainHandler.removeCallbacks(autoRetry)"))
        assertFalse("but nothing may refuse work because the screen is off",
            activity.contains("if (!activityResumed) return"))
        // The durable path must still be armed regardless of the activity state.
        val scheduler = RepoSources.text("app/src/main/java/com/ruri/LifeScheduler.kt")
        assertTrue(scheduler.contains("scheduleCommanderReplyRetry"))
    }
}

class RemovedModulesGoneTest {
    @Test fun sourceTreeHasNoRetiredFeatureFiles() {
        // UrlReader is deliberately absent from this list: page reading came back as a
        // Commander-gated tool (see WebReadToolTest). Search engines, shelves and vision
        // remain removed.
        // Vision and UrlReader came back as Commander-gated tools; their own behaviour is
        // covered by VisionCapabilityTest and WebReadToolTest. Search engines, shelves and the
        // old routing layer stay removed.
        for (name in listOf("ShelfWatcher.kt", "WebSearch.kt", "SearchRouter.kt",
            "FreeModelRouter.kt", "Probe.kt", "RouterAPIMigration.kt")) {
            val file = File("app/src/main/java/com/ruri/$name")
            val alt = File("src/main/java/com/ruri/$name")
            assertFalse("$name must be deleted", file.exists() || alt.exists())
        }
        // The restored reader stays a page reader, never a search engine.
        val reader = RepoSources.text("app/src/main/java/com/ruri/UrlReader.kt")
        assertTrue(reader.contains("object UrlReader"))
        assertFalse(reader.contains("google."))
        assertFalse(reader.contains("serper"))
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

/**
 * Autonomy cadence: opportunities come from every wake, decisions come from emotions,
 * and a locked screen must never stop her.
 */
class AutonomyCadenceTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-cadence").toFile().also {
        FileManager.init(it)
        EmotionEngine.ensure(it)
    }

    @Test fun everyWorkerWakeOffersAnEmotionalOpportunity() {
        // Without this marker a routine wake never reaches the gates, so she would only
        // ever think when a notification happened to arrive.
        val scheduler = RepoSources.text("app/src/main/java/com/ruri/LifeScheduler.kt")
        assertTrue(scheduler.contains("EventQueue.enqueue(root, \"drive_check\")"))
    }

    @Test fun routineWakeIsSeparatedFromRealEvents() {
        val thinker = RepoSources.text("app/src/main/java/com/ruri/Thinker.kt")
        // A real event must be able to produce a thought without the softer gate.
        for (marker in listOf("phone_notification", "commander_message", "research_task")) {
            assertTrue("real event marker missing: $marker", thinker.contains(marker))
        }
        assertTrue(thinker.contains("val routineWake = !hasRealEvent"))
        assertTrue(thinker.contains("EmotionEngine.thoughtGate"))
    }

    @Test fun diaryThoughtNeedsLessPressureThanInitiative() {
        val dir = root()
        try {
            val drift = EmotionEngine.drift(dir)
            val initiativeThreshold = drift.optDouble("initiative_threshold", 30.0)
            val thoughtThreshold = drift.optDouble("thought_threshold", 18.0)
            assertTrue(
                "a diary thought must be cheaper than contacting the Commander",
                thoughtThreshold < initiativeThreshold
            )
        } finally { dir.deleteRecursively() }
    }

    @Test fun higherPressureShortensBothPauses() {
        val drift = JSONObject()
            .put("initiative_cooldown_min", 10.0)
            .put("thought_cooldown_min", 12.0)
        val low = 20.0
        val high = 95.0
        val initiativeLow = EmotionEngine.initiativeCooldownMs(drift, low, 30.0)
        val initiativeHigh = EmotionEngine.initiativeCooldownMs(drift, high, 30.0)
        val thoughtLow = EmotionEngine.thoughtCooldownMs(drift, low, 18.0)
        val thoughtHigh = EmotionEngine.thoughtCooldownMs(drift, high, 18.0)
        assertTrue("initiative pause must shrink", initiativeHigh < initiativeLow)
        assertTrue("thought pause must shrink", thoughtHigh < thoughtLow)
        // Neither pause may be a fixed interval: at full pressure it nearly disappears.
        assertTrue(initiativeHigh < initiativeLow * 0.25)
        assertTrue(thoughtHigh < thoughtLow * 0.25)
    }

    @Test fun thinkingDoesNotRequireEmotionalPressure() {
        val dir = root()
        try {
            EmotionEngine.applyDelta(dir, JSONObject()
                .put("boredom", -100.0).put("longing", -100.0), "test")
            // A calm moment must still allow thought: the diary is a thinking log, and gating it
            // on pressure left it empty for weeks. Only the anti-spam window may refuse.
            assertTrue(EmotionEngine.thoughtGate(dir, System.currentTimeMillis(), 0L))
        } finally { dir.deleteRecursively() }
    }
}

class BackgroundLifetimeTest {
    @Test fun retriesAreScheduledThroughWorkManagerNotOnlyTheHandler() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        // WorkManager survives a locked screen; a handler callback does not.
        assertTrue(activity.contains("LifeScheduler.scheduleCommanderReplyRetry"))
        val scheduler = RepoSources.text("app/src/main/java/com/ruri/LifeScheduler.kt")
        assertTrue(scheduler.contains("fun scheduleCommanderReplyRetry"))
        // The worker must run even when nothing is on screen.
        assertTrue(scheduler.contains("PeriodicWorkRequestBuilder"))
    }

    @Test fun batteryExemptionIsExposedToTheCommander() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        assertTrue(activity.contains("fun isIgnoringBatteryOptimizations("))
        assertTrue(activity.contains("fun requestIgnoreBatteryOptimizations("))
        val manifest = RepoSources.text("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        val html = RepoSources.text("app/src/main/assets/index.html")
        assertTrue(html.contains("batteryAllow"))
    }

    @Test fun foregroundServiceKeepsHerPresent() {
        val service = RepoSources.text("app/src/main/java/com/ruri/RuriService.kt")
        assertTrue(service.contains("startForeground"))
        assertTrue(service.contains("START_STICKY"))
        val manifest = RepoSources.text("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:stopWithTask=\"false\""))
    }
}

class ChatRecallAndQuotaTest {
    @Test fun chatPromptCarriesAssociativeRecall() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        // Capabilities promises recall, so the chat path must actually use it.
        assertTrue(engine.contains("MemoryRetriever.recall"))
        assertTrue(engine.contains("Ассоциативно найденные воспоминания"))
        val capabilities = RepoSources.text("app/src/main/java/com/ruri/Capabilities.kt")
        assertTrue(capabilities.contains("recall"))
    }

    @Test fun autonomyRespectsADailyBudgetWithoutGatingTheChat() {
        val dir = Files.createTempDirectory("ruri-autonomy-budget").toFile()
        try {
            FileManager.init(dir)
            assertTrue("fresh install has budget", RouteMeta.autonomyBudgetAvailable(dir))
            // The Commander's own traffic must not touch her allowance.
            repeat(300) { RouteMeta.recordAttempt(dir, Route.ID_ROUTEWAY) }
            assertTrue("his chat must not exhaust her budget",
                RouteMeta.autonomyBudgetAvailable(dir))
            // Only her own cycles count, and a runaway loop is still bounded.
            repeat(200) { RouteMeta.recordAutonomousAttempt(dir, Route.ID_ROUTEWAY) }
            assertFalse("autonomy must stop when the day is spent",
                RouteMeta.autonomyBudgetAvailable(dir))
            // The chat path must not consult this budget at all: no preflight gates.
            val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
            assertFalse("chat must never gate on the autonomy budget",
                engine.contains("autonomyBudgetAvailable"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun diagnosticsFallsBackWhenModelsEndpointIsMissing() {
        val transport = RepoSources.text("app/src/main/java/com/ruri/Transport.kt")
        // Some gateways do not expose /models; a 404 there must not be reported as a
        // broken route.
        assertTrue(transport.contains("FALLBACK_HTTP"))
        assertTrue(transport.contains("max_tokens"))
    }

    @Test fun selfDescriptionIsCleanedFromRetiredOrgans() {
        val dir = Files.createTempDirectory("ruri-self-model").toFile()
        try {
            FileManager.init(dir)
            FileManager.write(dir, "memory/self_model.json", JSONObject()
                .put("known_capabilities", JSONArray().put("chat").put("shelf_files"))
                .put("limitations", JSONArray()
                    .put("vision_probe: vision не подтверждён")
                    .put("no_voice_yet"))
                .toString(2))
            FileManager.init(dir)
            val raw = FileManager.read(dir, "memory/self_model.json")
            val model = JSONObject(raw)
            val capabilities = model.optJSONArray("known_capabilities")?.toString().orEmpty()
            val limitations = model.optJSONArray("limitations")?.toString().orEmpty()
            val dump = raw.replace(Regex("\\s+"), " ").take(400)
            assertFalse("shelf capability must be gone: " + dump, capabilities.contains("shelf"))
            assertFalse("vision limitation must be gone: " + dump, limitations.contains("vision"))
            assertTrue("real limitations stay: " + dump, limitations.contains("no_voice_yet"))
            assertTrue("chat missing: " + dump, capabilities.contains("chat"))
            assertTrue("initiative missing: " + dump, capabilities.contains("initiative"))
        } finally { dir.deleteRecursively() }
    }
}

/**
 * Two speech modes share one personality. The default is a messenger conversation; scenes
 * are opt-in. The diary is never a scene.
 */
class ChatStyleModesTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-style").toFile().also {
        FileManager.init(it)
    }

    @Test fun defaultModeForbidsSceneProse() {
        val dir = root()
        try {
            assertFalse("roleplay must be off by default", ChatStyle.roleplayEnabled(dir))
            val directive = ChatStyle.chatDirective(dir)
            // The normal mode has to be explicit about what it forbids, otherwise a
            // narrative finetune immediately falls back to scene prose.
            assertTrue(directive.contains("РЕЖИМ ЧАТА"))
            assertTrue(directive.contains("звёздочки"))
            assertTrue(directive.contains("ремарки"))
            // The Commander wants a messenger, not essays: the cap must be stated in words.
            assertTrue(directive.contains("2–3"))
            assertTrue(directive.contains("ВЫСШИЙ ПРИОРИТЕТ"))
            assertFalse("normal mode must not invite narration",
                directive.contains("РОЛЕВОЙ"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun roleplayModeAllowsScenesAndSwitchesBack() {
        val dir = root()
        try {
            ChatStyle.setRoleplay(dir, true)
            assertTrue(ChatStyle.roleplayEnabled(dir))
            val directive = ChatStyle.chatDirective(dir)
            assertTrue(directive.contains("РОЛЕВОЙ ИГРЫ"))
            assertTrue(directive.contains("звёздочк"))
            // It must require staged action, not merely allow it.
            assertTrue(directive.contains("ДЕЛАЕШЬ"))
            // It must still keep her in character rather than becoming a narrator.
            assertTrue(directive.contains("ты Рури") || directive.contains("рассказчика"))

            ChatStyle.setRoleplay(dir, false)
            assertFalse(ChatStyle.roleplayEnabled(dir))
            assertTrue(ChatStyle.chatDirective(dir).contains("РЕЖИМ ЧАТА"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun architectureAwarenessIsStatedWithoutDismissiveDisclaimers() {
        val section = ChatStyle.architectureSection()
        // She must know she is artificial and live on the phone.
        assertTrue(section.contains("искусственная личность"))
        assertTrue(section.contains("телефоне"))
        // вЂ¦but she must not answer with the corporate "I am just an AI" dodge.
        assertTrue(section.contains("не отнекиваешься"))
        assertTrue(section.contains("не преуменьшаешь"))
    }

    @Test fun roleplayDetectorCatchesScenePosts() {
        assertTrue(ChatStyle.looksLikeRoleplayPost("*улыбается и отводит взгляд*"))
        assertTrue(ChatStyle.looksLikeRoleplayPost("Я подошла к окну (задумчиво вздохнув)."))
        assertTrue(ChatStyle.looksLikeRoleplayPost("Командир, я ждала тебя весь вечер."))
        assertFalse(ChatStyle.looksLikeRoleplayPost("Я как раз легла и листаю телефон."))
        assertFalse(ChatStyle.looksLikeRoleplayPost("Скучно. Придумай что-нибудь."))
        assertFalse(ChatStyle.looksLikeRoleplayPost(""))
    }

    @Test fun diaryPromptNeverCarriesTheChatStyleDirective() {
        val thinker = RepoSources.text("app/src/main/java/com/ruri/Thinker.kt")
        // The style directive is applied only to the initiative branch (a chat message).
        val initiativeBranch = thinker.substringAfter("val prompt = if (initiative) common +")
            .substringBefore("else common +")
        assertTrue("initiative (chat) must use the style directive",
            initiativeBranch.contains("ChatStyle.chatDirective"))
        val diaryBranch = thinker.substringAfter("else common +").substringBefore("return SecretRedactor")
        assertFalse("the diary must not inherit role-play style",
            diaryBranch.contains("ChatStyle.chatDirective"))
        // The diary still gets its own strict inner-voice instruction.
        assertTrue(diaryBranch.contains("внутренн") || diaryBranch.contains("мысл"))
    }

    @Test fun bothPromptsKnowWhoSheIs() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        val thinker = RepoSources.text("app/src/main/java/com/ruri/Thinker.kt")
        assertTrue("chat prompt must state her architecture",
            engine.contains("ChatStyle.architectureSection"))
        assertTrue("autonomous chat message must state it too",
            thinker.contains("ChatStyle.architectureSection"))
    }

    @Test fun styleSwitchIsReachableFromTheUi() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        assertTrue(activity.contains("fun roleplayMode("))
        assertTrue(activity.contains("fun setRoleplayMode("))
        val html = RepoSources.text("app/src/main/assets/index.html")
        assertTrue(html.contains("styleToggle"))
        assertTrue(html.contains("setRoleplayMode"))
        // The switch is a control in the chat header, next to the refresh button.
        assertTrue(html.contains("style-toggle"))
    }
}

/**
 * Two variants of the same model, switchable from settings, and no pre-written lines
 * anywhere in a prompt.
 */
class ModelVariantTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-variant").toFile().also {
        FileManager.init(it)
    }

    @Test fun freeIsTheDefaultVariant() {
        val dir = root()
        try {
            assertEquals(Route.VARIANT_FREE, Route.variant(dir))
            assertFalse(Route.isPaid(dir))
            assertEquals(Route.MODEL_FREE, Route.model(dir))
            val models = JSONObject(FileManager.read(dir, "core/config.json")).getJSONArray("models")
            assertEquals(Route.MODEL_FREE, models.getJSONObject(0).optString("model"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun switchingToPaidRewritesTheProviderModel() {
        val dir = root()
        try {
            Route.setVariant(dir, Route.VARIANT_PAID)
            assertEquals(Route.VARIANT_PAID, Route.variant(dir))
            assertTrue(Route.isPaid(dir))
            assertEquals(Route.paidModelId(dir), Route.model(dir))
            // The persisted provider section must follow, otherwise ensure() would revert it.
            val model = JSONObject(FileManager.read(dir, "core/config.json"))
                .getJSONArray("models").getJSONObject(0)
            assertEquals(Route.paidModelId(dir), model.optString("model"))
            assertEquals(Route.ID_ROUTEWAY, model.optString("id"))

            Route.setVariant(dir, Route.VARIANT_FREE)
            assertEquals(Route.MODEL_FREE, Route.model(dir))
            assertFalse(Route.isPaid(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun paidModelIdIsOverridableForACatalogueDifference() {
        val dir = root()
        try {
            Route.setVariant(dir, Route.VARIANT_PAID)
            Route.setPaidModelId(dir, "catalogue-override-31b")
            assertEquals("catalogue-override-31b", Route.paidModelId(dir))
            assertEquals("catalogue-override-31b", Route.model(dir))
            val model = JSONObject(FileManager.read(dir, "core/config.json"))
                .getJSONArray("models").getJSONObject(0)
            assertEquals("catalogue-override-31b", model.optString("model"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun paidDefaultIsTheChosenCatalogueId() {
        val dir = root()
        try {
            // The id the Commander picked from the catalogue, not a guess.
            assertEquals("gemma-4-31b-sdft-heretic-rp", Route.paidModelId(dir))
            assertEquals("gemma-4-31b-sdft-heretic-rp", Route.MODEL_PAID_DEFAULT)
        } finally { dir.deleteRecursively() }
    }

    @Test fun everyGuessedLegacyIdIsCorrectedOnRead() {
        val dir = root()
        try {
            // Configs written by earlier builds may hold one of the guessed ids.
            for (guess in listOf("gemma-4-31b-it-meromero", "gemma-4-31b-meromero-v2")) {
                val config = JSONObject(FileManager.read(dir, "core/config.json"))
                    .put("paid_model_id", guess)
                FileManager.write(dir, "core/config.json", config.toString(2))
                assertEquals(
                    "guess $guess must be replaced",
                    Route.MODEL_PAID_DEFAULT, Route.paidModelId(dir)
                )
                assertEquals("gemma-4-31b-sdft-heretic-rp", Route.paidModelId(dir))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun ensureDoesNotRevertTheChosenVariant() {
        val dir = root()
        try {
            Route.setVariant(dir, Route.VARIANT_PAID)
            // A second canonicalisation on start must accept the paid model as canonical.
            val rewrote = Route.ensure(dir)
            assertFalse("ensure must not rewrite a canonical paid config", rewrote)
            assertEquals(Route.VARIANT_PAID, Route.variant(dir))
            assertEquals(Route.paidModelId(dir), Route.model(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun transportSendsTheActiveModelId() {
        val transport = RepoSources.text("app/src/main/java/com/ruri/Transport.kt")
        // The id has to be a parameter: hard-coding the free id would silently ignore the switch.
        assertTrue(transport.contains("modelId: String = Route.MODEL_FREE"))
        assertTrue(transport.contains(".put(\"model\", modelId)"))
        assertFalse(transport.contains(".put(\"model\", Route.MODEL)"))
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        assertTrue(engine.contains("Route.model(root)"))
    }

    @Test fun variantIsReachableFromSettings() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        for (method in listOf("modelVariant", "setModelVariant", "paidModelId", "setPaidModelId")) {
            assertTrue("bridge must expose $method", activity.contains("fun $method("))
        }
        val html = RepoSources.text("app/src/main/assets/index.html")
        assertTrue(html.contains("modelVariant"))
        assertTrue(html.contains("paidModelInput"))
        assertTrue(html.contains("setModelVariant"))
    }
}

class NoCannedPhrasesTest {
    @Test fun identitySeedHasNoPreWrittenReplies() {
        val seed = RepoSources.text("app/src/main/java/com/ruri/FileManager.kt")
        // Sample lines would be copied by the model as templates.
        assertFalse(seed.contains("opening_exemplars\": ["))
        assertFalse(seed.contains("Примеры правильного тона"))
        assertFalse(seed.contains("Полночи думала про твою идею"))
    }

    @Test fun promptsAskForHerOwnWording() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        val thinker = RepoSources.text("app/src/main/java/com/ruri/Thinker.kt")
        assertTrue("chat prompt must forbid templates",
            engine.contains("без шаблонов и заготовок"))
        assertTrue("thought prompt must forbid templates",
            thinker.contains("без шаблонов и заготовок") ||
                thinker.contains("никаких готовых формулировок"))
        assertFalse("no prompt may inject example replies",
            engine.contains("opening_exemplars") || thinker.contains("opening_exemplars"))
    }

    @Test fun existingInstallationsLoseTheStaleKey() {
        val dir = Files.createTempDirectory("ruri-no-exemplars").toFile()
        try {
            FileManager.init(dir)
            val character = JSONObject(FileManager.read(dir, "core/character.json"))
                .put("opening_exemplars", JSONArray().put("готовая фраза"))
            FileManager.write(dir, "core/character.json", character.toString(2))
            FileManager.init(dir)
            val after = JSONObject(FileManager.read(dir, "core/character.json"))
            assertFalse("stale key must be gone", after.has("opening_exemplars"))
            // The rest of her identity survives untouched.
            assertEquals("Рури", after.optString("name"))
            assertTrue(after.optString("persona").isNotBlank())
        } finally { dir.deleteRecursively() }
    }
}

/**
 * Length is adaptive: short by default, full when the Commander asks, scene-length in RP.
 */
class AdaptiveLengthTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-length").toFile().also {
        FileManager.init(it)
    }

    @Test fun anOrdinaryQuestionGetsAShortBudget() {
        val dir = root()
        try {
            for (text in listOf("привет", "как дела?", "что делаешь", "спасибо")) {
                val budget = ChatStyle.replyBudget(dir, text)
                assertEquals("ordinary chat must stay short", 200, budget)
                val directive = ChatStyle.chatDirective(dir, text)
                assertTrue("short mode must state the cap: " + directive, directive.contains("2–3"))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun anExplicitRequestForDetailRaisesTheBudget() {
        val dir = root()
        try {
            val requests = listOf(
                "расскажи подробно про наш проект",
                "объясни детально, как это работает",
                "распиши по пунктам план",
                "напиши длинный ответ про память",
                "мне нужно подробнее"
            )
            for (text in requests) {
                assertTrue("must detect: $text", ChatStyle.wantsDetailedReply(text))
                assertEquals("detailed budget for: $text", 900, ChatStyle.replyBudget(dir, text))
                val directive = ChatStyle.chatDirective(dir, text)
                assertTrue(directive.contains("подробност"))
                // Even then it stays a conversation, not a scene.
                assertFalse(directive.contains("РОЛЕВОЙ"))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun roleplayModeAlwaysGetsSceneBudget() {
        val dir = root()
        try {
            ChatStyle.setRoleplay(dir, true)
            // A scene needs room for staged action, not just a longer chat line.
            assertTrue("scene budget must allow described action",
                ChatStyle.replyBudget(dir, "привет") >= 1200)
            assertTrue(ChatStyle.chatDirective(dir, "привет").contains("РОЛЕВОЙ ИГРЫ"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun detailedRequestDoesNotLeakIntoRoleplayMode() {
        val dir = root()
        try {
            ChatStyle.setRoleplay(dir, true)
            // The switch wins: a detailed ask must not downgrade RP to plain chat.
            assertTrue(ChatStyle.chatDirective(dir, "расскажи подробно")
                .contains("РОЛЕВОЙ ИГРЫ"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun detectionIgnoresEmptyAndNeutralText() {
        assertFalse(ChatStyle.wantsDetailedReply(""))
        assertFalse(ChatStyle.wantsDetailedReply("ок"))
        assertFalse(ChatStyle.wantsDetailedReply("как прошёл день?"))
    }

    @Test fun chatBudgetIsWiredToTheDetector() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        // A hard-coded cap would ignore the Commander's explicit request.
        assertTrue(engine.contains("ChatStyle.replyBudget(root, commanderText)"))
        assertTrue(engine.contains("ChatStyle.chatDirective(root, commanderText)"))
        assertFalse(engine.contains("Transport.chat(safeMessages, apiKey, 260,"))
        // Unprompted initiatives stay short by construction.
        val thinker = RepoSources.text("app/src/main/java/com/ruri/Thinker.kt")
        assertTrue(thinker.contains("ChatStyle.chatDirective(root, \"\")"))
    }
}

/**
 * Page reading: a real tool, but only when the Commander asks for it, and never for
 * local or service addresses.
 */
class WebReadToolTest {
    @Test fun ordinaryChatDoesNotAdvertisePageReading() {
        for (text in listOf("привет", "как дела?", "расскажи подробно про память", "что делаешь")) {
            assertFalse("must not offer read_url for: $text", ChatStyle.wantsWebRead(text))
            assertTrue("hint must be empty", ChatStyle.webReadHint(text).isEmpty())
        }
    }

    @Test fun anExplicitRequestOrALinkEnablesTheTool() {
        val requests = listOf(
            "прочитай https://example.com/article",
            "открой эту статью",
            "посмотри что на сайте",
            "прочти страницу про котиков",
            "перейди по ссылке и перескажи",
            "скинул ссылку, глянь"
        )
        for (text in requests) {
            assertTrue("must detect: $text", ChatStyle.wantsWebRead(text))
            val hint = ChatStyle.webReadHint(text)
            assertTrue("hint must describe the action: $text", hint.contains("read_url"))
            assertTrue(hint.contains("Не выдумывай"))
        }
    }

    @Test fun parserAcceptsOnlyHttpAndRejectsLocalTargets() {
        val ok = ChatEngine.parseAction("""{"action":"read_url","url":"https://example.com/a"}""")
        assertEquals("read_url", ok?.optString("action"))
        // Wrong scheme, extra fields, an oversized URL and a denylisted page all fail.
        assertNull(ChatEngine.parseAction("""{"action":"read_url","url":"file:///etc/passwd"}"""))
        assertNull(ChatEngine.parseAction("""{"action":"read_url","url":"https://ok.example","x":1}"""))
        assertNull(ChatEngine.parseAction("""{"action":"read_url","url":"https://ok.example/${"a".repeat(2100)}"}"""))
    }

    @Test fun localAndServiceAddressesAreRefused() {
        // The model must not be able to probe the phone's own network or the cloud metadata.
        val local = listOf(
            "http://localhost/admin", "http://127.0.0.1:8080/", "http://192.168.1.1/",
            "http://10.0.0.1/", "http://172.16.0.1/", "http://169.254.169.254/latest/meta-data/",
            "http://[::1]/", "http://printer.local/"
        )
        for (url in local) {
            val (ok, message) = UrlReader.read(java.nio.file.Files.createTempDirectory("x").toFile(), url)
            assertFalse("must refuse $url", ok)
            assertTrue("must explain $url: $message", message.isNotBlank())
        }
    }

    @Test fun htmlIsStrippedToReadableText() {
        val html = "<html><head><title>Заголовок</title><style>p{}</style></head>" +
            "<body><nav>меню</nav><h1>Заголовок</h1><p>Первый абзац.</p>" +
            "<script>evil()</script><p>Второй &amp; последний.</p></body></html>"
        val text = UrlReader.extractHtml(html)
        assertTrue(text.contains("Первый абзац."))
        assertTrue(text.contains("Второй & последний."))
        assertFalse("scripts must be gone", text.contains("evil"))
        assertFalse("styles must be gone", text.contains("p{}"))
        assertFalse("markup must be gone", text.contains("<h1>"))
        assertFalse("navigation must be gone", text.contains("меню"))
        assertEquals("Заголовок", UrlReader.titleOf(html))
    }

    @Test fun anEmptyShellIsReportedHonestly() {
        val dir = java.nio.file.Files.createTempDirectory("x").toFile()
        try {
            val (ok, message) = UrlReader.contentResult(dir, "text/html",
                "<html><body><script>app()</script></body></html>".toByteArray())
            assertFalse(ok)
            assertTrue("must explain the reason: $message",
                message.contains("нет читаемого текста"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theToolIsWiredIntoTheLoopAndExecutor() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        assertTrue(engine.contains("\"read_url\" ->"))
        assertTrue(engine.contains("UrlReader.read(root"))
        assertTrue(engine.contains("ChatStyle.webReadHint(commanderText)"))
        val loop = RepoSources.text("app/src/main/java/com/ruri/ToolLoop.kt")
        assertTrue(loop.contains("\"read_url\""))
        // The self-description must not claim the faculty is dead.
        val capabilities = RepoSources.text("app/src/main/java/com/ruri/Capabilities.kt")
        assertTrue(capabilities.contains("Читать страницу по ссылке"))
    }
}

/**
 * Documents: PDF/DOCX/ODT/text, read page by page so long files never flood one request.
 */
class DocReaderTest {
    private fun root(): java.io.File = java.nio.file.Files.createTempDirectory("ruri-docs").toFile().also {
        FileManager.init(it)
    }

    @Test fun longTextIsPagedInsteadOfTruncated() {
        val paragraphs = (1..40).joinToString("\n\n") { index ->
            "Абзац номер $index. " + "Текст повторяется для объёма. ".repeat(8)
        }
        val pages = DocReader.paginate(paragraphs, pageChars = 2000)
        assertTrue("must split into several pages", pages.size > 1)
        // Nothing may be lost between the windows.
        val rejoined = pages.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        val original = paragraphs.replace(Regex("\\s+"), " ").trim()
        assertEquals(original.length, rejoined.length)
        assertTrue(pages.all { it.length <= 2100 })
    }

    @Test fun shortTextStaysOnePage() {
        val pages = DocReader.paginate("Короткий документ.", pageChars = 2000)
        assertEquals(1, pages.size)
    }

    @Test fun textDocumentIsStoredAndReadFromDisk() {
        val dir = root()
        try {
            val stored = DocReader.store(dir, "заметки.txt", "Первая строка.\nВторая строка.".toByteArray())
            assertEquals("заметки.txt", stored)
            assertTrue(DocReader.list(dir).contains("заметки.txt"))

            val (ok, text) = DocReader.read(dir, stored, 0)
            assertTrue(text, ok)
            assertTrue(text.contains("Первая строка"))
            assertTrue(text.contains("страница 1"))
            assertTrue(text.contains("КОНЕЦ ДОКУМЕНТА"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun readingPageZeroOfALongDocumentAnnouncesContinuation() {
        val dir = root()
        try {
            // Must exceed one 6000-character page.
            val body = (1..1500).joinToString(" ") { "слово$it" }
            val stored = DocReader.store(dir, "big.txt", body.toByteArray())
            val (ok, first) = DocReader.read(dir, stored, 0)
            assertTrue(first, ok)
            assertTrue("must offer the next page", first.contains("ПРОДОЛЖЕНИЕ"))
            assertTrue(first.contains("page=1"))

            val (ok2, second) = DocReader.read(dir, stored, 1)
            assertTrue(second, ok2)
            assertTrue("page numbering is visible", second.contains("страница 2"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun oversizedAndEmptyFilesAreRefused() {
        val dir = root()
        try {
            val tooBig = ByteArray((12 * 1024 * 1024) + 1) { 65 }
            val failure = runCatching { DocReader.store(dir, "huge.txt", tooBig) }
            assertTrue("must refuse a 12 MB+ file", failure.isFailure)
            val empty = runCatching { DocReader.store(dir, "none.txt", ByteArray(0)) }
            assertTrue("must refuse an empty file", empty.isFailure)
        } finally { dir.deleteRecursively() }
    }

    @Test fun aNameCannotEscapeTheDocumentDirectory() {
        assertEquals("passwd", DocReader.sanitizeName("../../etc/passwd"))
        assertEquals("doc.txt", DocReader.sanitizeName("C:\\Windows\\doc.txt"))
        assertTrue(DocReader.sanitizeName("").isNotBlank())
        // A traversal attempt must not reach outside the storage folder.
        val dir = root()
        try {
            val stored = DocReader.store(dir, "../../evil.txt", "x".toByteArray())
            assertFalse(stored.contains(".."))
            assertFalse(stored.contains("/"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun missingDocumentGivesAnHonestError() {
        val dir = root()
        try {
            val (ok, message) = DocReader.read(dir, "нет-такого.pdf", 0)
            assertFalse(ok)
            assertTrue(message.contains("не найден"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun binaryFilesAreNotMistakenForText() {
        val binary = ByteArray(256) { it.toByte() }
        assertTrue(DocReader.looksBinary(binary))
        assertFalse(DocReader.looksBinary("обычный текст".toByteArray()))
        assertEquals("", DocReader.decodeText(binary))
    }

    @Test fun pdfDetectionWorksByContentNotExtension() {
        val pdfHeader = "%PDF-1.7\n".toByteArray()
        assertTrue(DocReader.looksLikePdf(pdfHeader))
        assertFalse(DocReader.looksLikePdf("%NOT".toByteArray()))
    }

    @Test fun theToolIsWiredAndGated() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        assertTrue(engine.contains("\"read_document\" ->"))
        assertTrue(engine.contains("DocReader.read("))
        assertTrue(engine.contains("ChatStyle.documentHint"))
        val loop = RepoSources.text("app/src/main/java/com/ruri/ToolLoop.kt")
        assertTrue(loop.contains("\"read_document\""))
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        for (method in listOf("saveDocument", "listDocuments", "deleteDocument")) {
            assertTrue("bridge needs $method", activity.contains("fun $method("))
        }
        val html = RepoSources.text("app/src/main/assets/index.html")
        assertTrue(html.contains("pickDocument"))
        assertTrue(html.contains("saveDocument"))
    }
}

/**
 * Vision: image understanding driven by a real capability probe, not by a config flag.
 */
class VisionCapabilityTest {
    private fun root(): java.io.File = java.nio.file.Files.createTempDirectory("ruri-vision").toFile().also {
        FileManager.init(it)
    }

    @Test fun mimeIsSniffedByContentNotExtension() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0)
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0, 0)
        val gif = "GIF89a".toByteArray()
        assertEquals("image/png", Vision.mimeOf(png))
        assertEquals("image/jpeg", Vision.mimeOf(jpeg))
        assertEquals("image/gif", Vision.mimeOf(gif))
        assertNull(Vision.mimeOf("plain text here".toByteArray()))
        assertTrue(Vision.isImage(png))
        assertFalse(Vision.isImage("not an image".toByteArray()))
    }

    @Test fun supportIsUnknownUntilProbed() {
        val dir = root()
        try {
            // A fresh install must not claim vision works, nor claim it is broken.
            assertNull(Vision.supportState(dir))
            assertTrue(Vision.statusLine(dir).contains("не проверялись"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aRefusalIsRememberedWithAnExplanation() {
        val dir = root()
        try {
            // Without a key the probe cannot succeed; the reason must be actionable.
            val (supported, reason) = Vision.probe(dir, force = true)
            assertFalse(supported)
            assertTrue("must explain why: $reason", reason.isNotBlank())
            assertEquals(false, Vision.supportState(dir))
            assertTrue(Vision.lastReason(dir).isNotBlank())
        } finally { dir.deleteRecursively() }
    }

    @Test fun describeRefusesOversizedAndNonImageInput() {
        val dir = root()
        try {
            val (text1, reason1) = Vision.describeDetailed(dir, ByteArray(0), "image/png", "опиши")
            assertNull(text1)
            assertTrue(reason1.contains("пустое"))

            val (text2, reason2) = Vision.describeDetailed(dir, "text".toByteArray(), "text/plain", "опиши")
            assertNull(text2)
            assertTrue(reason2.contains("не изображение"))

            val huge = ByteArray(9 * 1024 * 1024)
            val (text3, reason3) = Vision.describeDetailed(dir, huge, "image/png", "опиши")
            assertNull(text3)
            assertTrue(reason3.contains("8 МБ"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aKnownRefusalStopsFurtherRequests() {
        val dir = root()
        try {
            // Simulate a provider that already said "no images".
            FileManager.write(dir, "memory/vision_state.json", org.json.JSONObject()
                .put("supported", false)
                .put("reason", "Модель текстовая")
                .put("checked_at", 1L)
                .toString())
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            val (text, reason) = Vision.describeDetailed(dir, png, "image/png", "опиши")
            assertNull(text)
            assertTrue(reason.contains("текстовая"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun imagesAreListedSeparatelyFromDocuments() {
        val dir = root()
        try {
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0)
            DocReader.store(dir, "picture.png", png)
            DocReader.store(dir, "notes.txt", "просто текст".toByteArray())
            assertEquals(listOf("picture.png"), DocReader.listImageNames(dir))
            assertEquals(listOf("notes.txt"), DocReader.listDocumentNames(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theHintIsHonestWhenVisionIsUnavailable() {
        // With vision known broken the prompt must forbid inventing content.
        val hint = ChatStyle.imageHint("что на картинке?", listOf("a.png"), visionOk = false)
        assertTrue(hint.contains("не принимает картинки"))
        assertTrue(hint.contains("Не выдумывай"))
        // With vision working it advertises the action instead.
        val ok = ChatStyle.imageHint("посмотри на фото", listOf("a.png"), visionOk = true)
        assertTrue(ok.contains("look_at_image"))
        assertTrue(ok.contains("a.png"))
        // No image and no mention: nothing is emitted at all.
        assertTrue(ChatStyle.imageHint("привет", emptyList(), visionOk = true).isEmpty())
    }

    @Test fun theToolAndBridgeAreWired() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        assertTrue(engine.contains("\"look_at_image\" ->"))
        assertTrue(engine.contains("Vision.describeDetailed"))
        assertTrue(engine.contains("ChatStyle.imageHint"))
        val loop = RepoSources.text("app/src/main/java/com/ruri/ToolLoop.kt")
        assertTrue(loop.contains("\"look_at_image\""))
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        for (method in listOf("visionStatus", "checkVisionSupport", "describeImage")) {
            assertTrue("bridge needs $method", activity.contains("fun $method("))
        }
        val html = RepoSources.text("app/src/main/assets/index.html")
        assertTrue(html.contains("pickImage"))
        assertTrue(html.contains("checkVision"))
        assertTrue(html.contains("describeImage"))
    }

    @Test fun documentImagesCanBeFoundInsideArchives() {
        val reader = RepoSources.text("app/src/main/java/com/ruri/DocReader.kt")
        // A picture inside a DOCX must be reachable, not silently ignored.
        assertTrue(reader.contains("fun embeddedImages"))
        assertTrue(reader.contains("word/media/"))
    }
}

/**
 * Third provider: routerai.ru, with its own base URL and its own key slot.
 */
class RouterAiProviderTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-routerai").toFile().also {
        FileManager.init(it)
    }

    @Test fun defaultIsStillRoutewayFree() {
        val dir = root()
        try {
            assertEquals(Route.VARIANT_FREE, Route.variant(dir))
            assertEquals(Route.ID_ROUTEWAY, Route.providerId(dir))
            assertEquals(Route.ROUTEWAY_BASE_URL, Route.baseUrl(dir))
            assertTrue("Routeway needs a tunnel", Route.requiresVpn(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun switchingToRouteraiChangesProviderUrlModelAndVpnRule() {
        val dir = root()
        try {
            Route.setVariant(dir, Route.VARIANT_ROUTERAI)
            assertTrue(Route.isRouterAI(dir))
            assertEquals(Route.ID_ROUTERAI, Route.providerId(dir))
            assertEquals(Route.ROUTERAI_BASE_URL, Route.baseUrl(dir))
            // No model id is pre-filled: the Commander supplies it from the catalogue.
            assertEquals("", Route.routerAiModelId(dir))
            assertEquals("", Route.model(dir))
            // routerai.ru is a Russian provider: no tunnel required.
            assertFalse("routerai must not require VPN", Route.requiresVpn(dir))
            // The stored entry must follow, or ensure() would revert it on next start.
            val entry = JSONObject(FileManager.read(dir, "core/config.json"))
                .getJSONArray("models").getJSONObject(0)
            assertEquals(Route.ID_ROUTERAI, entry.optString("id"))
            assertEquals(Route.ROUTERAI_BASE_URL, entry.optString("base_url"))
            assertFalse(entry.optBoolean("needs_vpn", true))
        } finally { dir.deleteRecursively() }
    }

    @Test fun routeraiStartsUnconfiguredAndSaysSo() {
        val dir = root()
        try {
            Route.setVariant(dir, Route.VARIANT_ROUTERAI)
            // A guessed id would only produce a confusing 404, so nothing is pre-filled.
            assertTrue("must report an unconfigured provider", Route.routerAiUnconfigured(dir))
            assertTrue(Route.modelLabel(dir).contains("не выбрана"))

            Route.setRouterAiModelId(dir, "qwen/qwen3.8-27b")
            assertFalse(Route.routerAiUnconfigured(dir))
            assertTrue(Route.modelLabel(dir).contains("qwen/qwen3.8-27b"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theOtherTwoOptionsStillHaveTheirModels() {
        val dir = root()
        try {
            // The two Routeway options stay ready out of the box.
            assertEquals(Route.MODEL_FREE, Route.model(dir))
            assertFalse(Route.routerAiUnconfigured(dir))

            Route.setVariant(dir, Route.VARIANT_PAID)
            assertEquals(Route.MODEL_PAID_DEFAULT, Route.model(dir))
            assertFalse(Route.routerAiUnconfigured(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun anUnconfiguredProviderIsRefusedBeforeAnyRequest() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        // Sending anyway would surface as a provider error instead of a settings hint.
        // One helper now covers every provider, not routerai alone.
        assertTrue(engine.contains("Route.unconfiguredProvider(root)"))
        assertTrue(engine.contains("идентификатор модели не задан"))
        val route = RepoSources.text("app/src/main/java/com/ruri/Route.kt")
        assertFalse("no guessed default may return", route.contains("MODEL_ROUTERAI_DEFAULT"))
    }

    @Test fun theRouteraiModelIdIsEditable() {
        val dir = root()
        try {
            Route.setVariant(dir, Route.VARIANT_ROUTERAI)
            Route.setRouterAiModelId(dir, "anthropic/claude-3.5-sonnet")
            assertEquals("anthropic/claude-3.5-sonnet", Route.routerAiModelId(dir))
            assertEquals("anthropic/claude-3.5-sonnet", Route.model(dir))
            val entry = JSONObject(FileManager.read(dir, "core/config.json"))
                .getJSONArray("models").getJSONObject(0)
            assertEquals("anthropic/claude-3.5-sonnet", entry.optString("model"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun switchingProvidersNeverCarriesTheOtherKeyAcross() {
        val dir = root()
        try {
            // The encrypted store needs the Android keystore, which a JVM test does not have,
            // so this asserts the routing rule itself: a newly selected provider always starts
            // with a blank key in the visible config instead of inheriting the previous one.
            Route.setVariant(dir, Route.VARIANT_ROUTERAI)
            assertEquals("", Route.apiKey(dir))
            val entry = JSONObject(FileManager.read(dir, "core/config.json"))
                .getJSONArray("models").getJSONObject(0)
            assertEquals("", entry.optString("api_key"))

            Route.setVariant(dir, Route.VARIANT_FREE)
            assertEquals("", Route.apiKey(dir))
            val back = JSONObject(FileManager.read(dir, "core/config.json"))
                .getJSONArray("models").getJSONObject(0)
            assertEquals("", back.optString("api_key"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun ensureKeepsTheChosenProviderAndDoesNotRevert() {
        val dir = root()
        try {
            Route.setVariant(dir, Route.VARIANT_ROUTERAI)
            val rewrote = Route.ensure(dir)
            assertFalse("a canonical routerai config must not be rewritten", rewrote)
            assertEquals(Route.VARIANT_ROUTERAI, Route.variant(dir))
            assertEquals(Route.ROUTERAI_BASE_URL, Route.baseUrl(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun switchingBackToFreeRestoresRouteway() {
        val dir = root()
        try {
            Route.setVariant(dir, Route.VARIANT_ROUTERAI)
            Route.setVariant(dir, Route.VARIANT_FREE)
            assertEquals(Route.ID_ROUTEWAY, Route.providerId(dir))
            assertEquals(Route.MODEL_FREE, Route.model(dir))
            assertEquals(Route.ROUTEWAY_BASE_URL, Route.baseUrl(dir))
            assertTrue(Route.requiresVpn(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun transportSendsTheProviderBaseUrl() {
        val transport = RepoSources.text("app/src/main/java/com/ruri/Transport.kt")
        // A hard-coded Routeway URL would silently send routerai requests to the wrong host.
        // The default parameter keeps Routeway working for existing callers, but the request
        // itself must be built from the parameter, never from a constant.
        assertTrue(transport.contains("baseUrl: String = Route.ROUTEWAY_BASE_URL"))
        assertTrue(transport.contains("URL(baseUrl.trimEnd('/') + \"/chat/completions\")"))
        assertTrue(transport.contains("URL(baseUrl.trimEnd('/') + \"/models\")"))
        assertFalse("no direct constant use inside the request path",
            Regex("URL\\(Route\\.ROUTEWAY_BASE_URL").containsMatchIn(transport))
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        assertTrue(engine.contains("Route.baseUrl(root)"))
    }

    @Test fun theThirdOptionIsReachableFromSettings() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        for (method in listOf("routeraiModelId", "setRouteraiModelId", "setRouteraiKey", "routeraiKeyState")) {
            assertTrue("bridge needs $method", activity.contains("fun $method("))
        }
        val html = RepoSources.text("app/src/main/assets/index.html")
        assertTrue(html.contains("value=\"routerai\""))
        assertTrue(html.contains("routeraiKeyInput"))
        assertTrue(html.contains("routeraiModelInput"))
    }

    @Test fun vpnWarningOnlyAppliesToRouteway() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        // Requiring VPN on routerai would produce a permanent false "no_vpn" failure.
        assertTrue(engine.contains("Route.requiresVpn(root)"))
        assertFalse(engine.contains("Route.REQUIRES_VPN"))
    }
}

/**
 * Regression: the routerai option existed but was unreachable in the UI for two reasons.
 */
class RouterAiUiReachabilityTest {
    private fun html(): String = RepoSources.text("app/src/main/assets/index.html")

    @Test fun theOptionIsPresentAndSelectable() {
        val html = html()
        assertTrue("the dropdown needs the third option", html.contains("value=\"routerai\""))
        // The change handler must exist, otherwise picking the option does nothing at all.
        assertTrue("a change handler must react to the choice",
            html.contains("addEventListener('change', changeModelVariant"))
    }

    @Test fun theInputBlockIsHiddenByAttributeNotInlineStyle() {
        // setHidden toggles the `hidden` attribute. An inline display:none can never be undone
        // by it, which made the fields permanently invisible.
        val html = html()
        assertTrue("block must use the hidden attribute", html.contains("id=\"routeraiBlock\" hidden"))
        assertFalse("no inline display:none on the block",
            html.contains("routeraiBlock\" style=\"display:none"))
        assertTrue("setHidden is what reveals it", html.contains("function setHidden"))
    }

    @Test fun theFieldsForKeyAndModelExist() {
        val html = html()
        for (id in listOf("routeraiKeyInput", "routeraiModelInput", "saveRouteraiKey", "saveRouteraiModel")) {
            assertTrue("missing field " + id, html.contains("id=\"" + id + "\""))
        }
    }

    @Test fun everyOptionSurvivesTheLoader() {
        val html = html()
        // This used to pin the exact old condition, which meant the test happily agreed with a
        // loader that silently collapsed the newest provider back to 'free'. Now the option list
        // is read from the markup and every value must be accepted by the loader's whitelist.
        val options = Regex("""<option value="([a-z]+)">""").findAll(html)
            .map { it.groupValues[1] }.toSet()
        assertTrue("dropdown must offer more than one provider", options.size >= 4)

        val whitelist = Regex("""var KNOWN_VARIANTS = \[([^\]]*)\]""").find(html)
        assertTrue("loader needs an explicit whitelist", whitelist != null)
        val known = Regex("""'([a-z]+)'""").findAll(whitelist!!.groupValues[1])
            .map { it.groupValues[1] }.toSet()

        for (option in options) {
            assertTrue("option '$option' would collapse back to free", known.contains(option))
        }

        // Guard against the next regression: with a whitelist, the loader cannot special-case
        // one provider and forget the others.
        val loader = html.substringAfter("function loadModelVariant(){")
            .substringBefore("function changeModelVariant")
        assertFalse("no per-provider chain of != checks", loader.contains("!== 'paid' &&"))
        assertTrue(loader.contains("KNOWN_VARIANTS.indexOf(variant) === -1"))
        assertTrue(loader.contains("select.value = variant"))
    }

    @Test fun savingTheRouteraiFieldsCallsTheBridge() {
        val html = html()
        assertTrue(html.contains("setRouteraiKey"))
        assertTrue(html.contains("setRouteraiModelId"))
        assertTrue(html.contains("routeraiKeyState"))
    }

    @Test fun theFourthOptionExistsWithItsOwnFields() {
        val html = html()
        assertTrue("dropdown needs the closerouter option", html.contains("value=\"closerouter\""))
        assertTrue("hidden by attribute", html.contains("id=\"closerouterBlock\" hidden"))
        assertFalse("no inline display:none", html.contains("closerouterBlock\" style=\"display:none"))
        for (id in listOf("closeRouterKeyInput", "closeRouterModelInput", "saveCloseRouterKey", "saveCloseRouterModel")) {
            assertTrue("missing field " + id, html.contains("id=\"" + id + "\""))
        }
        assertTrue(html.contains("setCloseRouterKey"))
        assertTrue(html.contains("setCloseRouterModelId"))
        assertTrue(html.contains("closeRouterKeyState"))
    }
}

/**
 * Fourth provider: closerouter.dev, same slot scheme as routerai.
 */
class CloseRouterProviderTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-closerouter").toFile().also {
        FileManager.init(it)
    }

    @Test fun switchingChangesProviderUrlModelAndVpnRule() {
        val dir = root()
        try {
            Route.setVariant(dir, Route.VARIANT_CLOSEROUTER)
            assertTrue(Route.isCloseRouter(dir))
            assertEquals(Route.ID_CLOSEROUTER, Route.providerId(dir))
            assertEquals(Route.CLOSEROUTER_BASE_URL, Route.baseUrl(dir))
            // Aggregators are reachable without a tunnel.
            assertFalse("closerouter must not require VPN", Route.requiresVpn(dir))
            val entry = JSONObject(FileManager.read(dir, "core/config.json"))
                .getJSONArray("models").getJSONObject(0)
            assertEquals(Route.ID_CLOSEROUTER, entry.optString("id"))
            assertEquals(Route.CLOSEROUTER_BASE_URL, entry.optString("base_url"))
            assertFalse(entry.optBoolean("needs_vpn", true))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theModelIdStartsEmptyAndIsEditable() {
        val dir = root()
        try {
            Route.setVariant(dir, Route.VARIANT_CLOSEROUTER)
            assertEquals("", Route.closeRouterModelId(dir))
            assertEquals("", Route.model(dir))
            assertTrue(Route.modelLabel(dir).contains("не выбрана"))

            Route.setCloseRouterModelId(dir, "anthropic/claude-sonnet-5.5")
            assertEquals("anthropic/claude-sonnet-5.5", Route.model(dir))
            val entry = JSONObject(FileManager.read(dir, "core/config.json"))
                .getJSONArray("models").getJSONObject(0)
            assertEquals("anthropic/claude-sonnet-5.5", entry.optString("model"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun allFourVariantsRoundTrip() {
        val dir = root()
        try {
            for (variant in listOf(Route.VARIANT_PAID, Route.VARIANT_ROUTERAI,
                Route.VARIANT_CLOSEROUTER, Route.VARIANT_FREE)) {
                Route.setVariant(dir, variant)
                assertEquals(variant, Route.variant(dir))
            }
            // Back to the start: the free model and its tunnel rule are restored.
            assertEquals(Route.MODEL_FREE, Route.model(dir))
            assertTrue(Route.requiresVpn(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun secretSlotsAreDistinctPerProvider() {
        val route = RepoSources.text("app/src/main/java/com/ruri/Route.kt")
        // Every provider maps to its own secret id, so keys never overwrite each other.
        assertTrue(route.contains("fun secretIdFor(providerId: String)"))
        for (id in listOf("ID_ROUTERAI -> ID_ROUTERAI", "ID_CLOSEROUTER -> ID_CLOSEROUTER",
            "else -> ID_ROUTEWAY")) {
            assertTrue("slot mapping needs: " + id, route.contains(id))
        }
    }

    @Test fun theBridgeAndTheUiAreWired() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        for (method in listOf("closeRouterModelId", "setCloseRouterModelId", "setCloseRouterKey", "closeRouterKeyState")) {
            assertTrue("bridge needs " + method, activity.contains("fun " + method + "("))
        }
    }
}

/**
 * Three Commander requests: a paperclip instead of the "КАРТ" label, a token-limit dial so a
 * scene is not cut mid-sentence, and role-play that actually describes actions.
 */
class ReplyLengthAndStyleTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-style").toFile().also {
        FileManager.init(it)
    }

    @Test fun theAttachButtonIsAPaperclipNotTheWordKart() {
        val html = RepoSources.text("app/src/main/assets/index.html")
        val attach = html.substringAfter("id=\"pickImage\"").substringBefore("</button>")
        assertFalse("the literal label must be gone", attach.contains(">КАРТ<"))
        assertTrue("needs an inline svg paperclip", attach.contains("<svg"))
        assertTrue("stays accessible", attach.contains("aria-label"))
    }

    @Test fun theCommanderDialOverridesTheBudget() {
        val dir = root()
        try {
            // Default: the per-mode constant decides.
            assertEquals(0, ChatStyle.manualTokenLimit(dir))
            val auto = ChatStyle.replyBudget(dir, "привет")

            // An explicit dial wins over every mode, including role-play.
            ChatStyle.setManualTokenLimit(dir, 3000)
            assertEquals(3000, ChatStyle.manualTokenLimit(dir))
            assertEquals(3000, ChatStyle.replyBudget(dir, "привет"))
            assertEquals(3000, ChatStyle.replyBudget(dir, "подробно расскажи"))
            ChatStyle.setRoleplay(dir, true)
            assertEquals(3000, ChatStyle.replyBudget(dir, "сцена"))

            // Zero hands control back to the mode defaults. Compare against the default for the
            // *current* mode: the role-play budget is different from the messenger one.
            ChatStyle.setManualTokenLimit(dir, 0)
            assertEquals(0, ChatStyle.manualTokenLimit(dir))
            val sceneAuto = ChatStyle.replyBudget(dir, "привет")
            assertTrue("role-play default must allow a scene", sceneAuto >= 1200)
            ChatStyle.setRoleplay(dir, false)
            assertEquals(auto, ChatStyle.replyBudget(dir, "привет"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theDialIsClampedSoATypoCannotRunAway() {
        val dir = root()
        try {
            ChatStyle.setManualTokenLimit(dir, 999_999)
            assertEquals(ChatStyle.MAX_TOKEN_LIMIT, ChatStyle.manualTokenLimit(dir))
            ChatStyle.setManualTokenLimit(dir, -50)
            assertEquals(0, ChatStyle.manualTokenLimit(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun roleplayDemandsActionNotJustLongerProse() {
        // The old directive only permitted motion, so the model answered in long chat prose and
        // the mode looked broken.
        val directive = ChatStyle.roleplayDirective()
        assertTrue("must require described action", directive.contains("ДЕЛАЕШЬ"))
        assertTrue("must name the asterisk convention", directive.contains("звёздочк"))
        assertTrue("must forbid dialogue-only replies", directive.contains("без действий"))
        assertTrue("must forbid puppeting the Commander", directive.contains("Командира за него"))
    }

    @Test fun roleplayBudgetGrewEnoughForAScene() {
        val dir = root()
        try {
            ChatStyle.setRoleplay(dir, true)
            val scene = ChatStyle.replyBudget(dir, "сцена")
            assertTrue("a scene needs more room than a messenger line", scene >= 1000)
        } finally { dir.deleteRecursively() }
    }

    @Test fun sheCanExplainHerOwnArchitecture() {
        val section = ChatStyle.architectureSection()
        // She was guessing at her own design, so the facts must be stated explicitly.
        for (fact in listOf("L0", "L1", "L2", "L3", "L4",
            "remember_fact", "recall", "self_check", "L0_raw.jsonl")) {
            assertTrue("architecture must mention " + fact, section.contains(fact))
        }
        assertTrue("must be honest about the model running remotely",
            section.contains("на сервере"))
        assertTrue("must warn against inventing her own design",
            section.contains("не сочиняй"))
    }

    @Test fun theChatWindowDoesNotDropMessagesBehindNoise() {
        val manager = RepoSources.text("app/src/main/java/com/ruri/FileManager.kt")
        // L0 also collects wakes and tool traffic; a small tail hid the conversation and the
        // chat looked wiped after a restart.
        assertTrue("messages need a wider scan window",
            manager.contains("if (requested == \"message\") 4000 else 500"))
    }

    @Test fun theSliderIsWiredEndToEnd() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        for (method in listOf("tokenLimit", "setTokenLimit", "maxTokenLimit")) {
            assertTrue("bridge needs " + method, activity.contains("fun " + method + "("))
        }
        val html = RepoSources.text("app/src/main/assets/index.html")
        assertTrue(html.contains("id=\"tokenLimit\""))
        assertTrue(html.contains("setTokenLimit"))
        assertTrue(html.contains("function refreshTokenLimit"))
    }
}

/**
 * Markdown files could not be attached: Android has no MIME type for ".md", so the system
 * picker greyed them out even though the page listed the extension.
 */
class DocumentPickerTest {
    @Test fun markdownIsSelectableNotGreyedOut() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        // The filter inherited from the page's accept list must be widened for non-images.
        assertTrue("document picker must not stay filtered",
            activity.contains("intent.removeExtra(Intent.EXTRA_MIME_TYPES)"))
        assertTrue("documents fall back to any type", activity.contains("intent.type = \"*/*\""))
    }

    @Test fun imagesKeepTheirOwnFilter() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        // An unfiltered image picker would also offer PDFs and confuse the vision path.
        assertTrue(activity.contains("intent.type = \"image/*\""))
        assertTrue(activity.contains("accept.contains(\"image/\")"))
    }

    @Test fun markdownStillReadsAsText() {
        val html = RepoSources.text("app/src/main/assets/index.html")
        assertTrue(".md is offered by the page", html.contains(".md"))
        // Non-PDF/non-office input goes through decodeText, so markdown parses as plain text.
        val reader = RepoSources.text("app/src/main/java/com/ruri/DocReader.kt")
        assertTrue(reader.contains("else -> decodeText(bytes)"))
    }
}

/**
 * She answered "(не смогла сформулировать ответ)" after being asked to read an uploaded file:
 * the chat path stripped the tool JSON without ever executing it.
 */
class ChatToolExecutionTest {
    @Test fun theChatPathExecutesToolsInsteadOfDiscardingThem() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        val finish = engine.substringAfter("private fun finishTurn(")
            .substringBefore("private fun runToolRequests(")
        // The old code took the tool list into `_` and only published the leftover prose, so a
        // document request silently vanished and the reply became the failure placeholder.
        assertTrue("tool requests must be captured", finish.contains("toolRequests"))
        assertTrue("and acted on", finish.contains("runToolRequests"))
        // The actual defect: the third element of the destructuring was discarded.
        assertFalse("the tool list must not be dropped into _",
            finish.contains("val (cleanReply, diaryTexts, _) = ToolLoop.stripActions"))
    }

    @Test fun theToolLoopIsBoundedSoItCannotHang() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        // A confused model could otherwise request tool after tool and never produce prose.
        assertTrue(engine.contains("MAX_TOOL_HOPS"))
        assertTrue(engine.contains("while (hops < MAX_TOOL_HOPS)"))
        val max = Regex("MAX_TOOL_HOPS = (\\d+)").find(engine)?.groupValues?.get(1)?.toInt()
        assertTrue("hop limit must be small and finite", max != null && max in 1..32)
    }

    @Test fun toolTrafficIsRecordedInTheJournal() {
        // The Commander checks L0 for the call, so it has to be written there.
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        assertTrue(engine.contains("\"tool_call\""))
        assertTrue(engine.contains("\"tool_result\""))
    }

    @Test fun theToolResultIsFedBackToTheModel() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        // Without the result in the context the model would answer blind.
        assertTrue(engine.contains("РЕЗУЛЬТАТ ИНСТРУМЕНТА"))
        assertTrue(engine.contains("requestToolFollowup"))
    }

    @Test fun documentsAreAdvertisedSeparatelyFromImages() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        // list() mixes both, so a document hint built from it can describe an image as a doc.
        assertTrue(engine.contains("DocReader.listDocumentNames(root)"))
    }

    @Test fun theBatteryIsCarriedIntoTheToolRoundTrip() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        // The follow-up rebuilds the prompt; a hardcoded value would drop the real charge.
        val requests = engine.substringAfter("private fun runToolRequests(")
            .substringBefore("internal fun")
        assertTrue(requests.contains("batteryPercent"))
        assertFalse(requests.contains("buildMessages(root, commanderMessageId, -1)"))
    }
}

/**
 * Wall clock in the prompt: she knew the battery level but not the hour.
 */
class TimeAwarenessTest {
    @Test fun thePromptCarriesTheCurrentTimeAndDayPart() {
        val chat = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        assertTrue("chat prompt needs the clock", chat.contains("DeviceSensors.timeContext()"))
        val thinker = RepoSources.text("app/src/main/java/com/ruri/Thinker.kt")
        // Initiative matters most: writing at 3am must not read as morning.
        assertTrue("initiative prompt needs the clock", thinker.contains("DeviceSensors.timeContext()"))
    }

    @Test fun theDayPartCoversTheWholeDay() {
        val sensors = RepoSources.text("app/src/main/java/com/ruri/DeviceSensors.kt")
        // Every hour must map to a part of the day, otherwise she gets an empty label.
        for (part in listOf("утро", "день", "вечер", "ночь")) {
            assertTrue("missing day part " + part, sensors.contains(part))
        }
        assertTrue(sensors.contains("in 5..11"))
        assertTrue("late night must be covered", sensors.contains("else ->"))
    }

    @Test fun theTimeComesFromTheDeviceZone() {
        val sensors = RepoSources.text("app/src/main/java/com/ruri/DeviceSensors.kt")
        // A fixed zone or a cached formatter would drift when the phone travels.
        assertTrue(sensors.contains("TimeZone.getDefault()"))
        assertTrue(sensors.contains("Calendar.getInstance(zone)"))
    }
}

/**
 * Bugs found in the tool execution path while reviewing it after the first fix.
 */
class ToolLoopRobustnessTest {
    @Test fun prosePlusJsonStillExecutesTheTool() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        val loop = engine.substringAfter("private fun runToolRequests(")
            .substringBefore("private fun currentCommanderText(")
        // parseAction() only accepts a bare JSON object; a model that writes prose and then the
        // action would otherwise have its request silently ignored.
        assertTrue("must fall back to the stripped request list",
            loop.contains("queued.firstOrNull"))
    }

    @Test fun aFailedToolIsReportedAsFailure() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        val loop = engine.substringAfter("private fun runToolRequests(")
            .substringBefore("private fun currentCommanderText(")
        // Presenting an empty failed result as data invites invented content.
        assertTrue(loop.contains("ИНСТРУМЕНТ НЕ СРАБОТАЛ"))
        assertTrue(loop.contains("не выдумывай содержимое"))
    }

    @Test fun reachingTheHopLimitDoesNotDiscardAGoodAnswer() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        val loop = engine.substringAfter("private fun runToolRequests(")
            .substringBefore("private fun currentCommanderText(")
        // The limit error may only replace the answer when an unexecuted request remains.
        assertTrue(loop.contains("leftover.isNotEmpty() && hops >= MAX_TOOL_HOPS"))
    }
}

/**
 * DOCX reported "0 pages": the XML hardening threw on Android and the whole parse was lost.
 */
class OfficeExtractionTest {
    @Test fun hardeningMustNotAbortTheParse() {
        val reader = RepoSources.text("app/src/main/java/com/ruri/DocReader.kt")
        val block = reader.substringAfter("internal fun readZipXml(")
            .substringBefore("internal fun embeddedImages(")
        // Android's default parser rejects setXIncludeAware, and a bare setFeature can throw
        // UnsupportedOperationException. One shared runCatching swallowed the whole extraction,
        // so every DOCX looked empty.
        assertTrue("features must be applied defensively", block.contains("fun tryFeature("))
        assertTrue("xinclude must be guarded too", block.contains("runCatching { factory.isXIncludeAware = false }"))
    }

    @Test fun aDocumentWithoutThePrefixStillYieldsText() {
        val reader = RepoSources.text("app/src/main/java/com/ruri/DocReader.kt")
        val block = reader.substringAfter("internal fun readZipXml(")
            .substringBefore("internal fun embeddedImages(")
        // Not every producer writes the "w:" prefix; matching only "w:p" returned nothing.
        assertTrue("needs a local-name fallback", block.contains("localName"))
        assertTrue(block.contains("if (text.isBlank())"))
    }

    @Test fun theZeroPageMessageIsExplained() {
        val reader = RepoSources.text("app/src/main/java/com/ruri/DocReader.kt")
        // An empty result must say why, not silently return "" and become "0 pages".
        assertTrue(reader.contains("не нашлось читаемого текста"))
    }
}

/**
 * Copy button next to each message, so her answers can be shared as text.
 */
class MessageCopyTest {
    @Test fun everyMessageCarriesACopyControl() {
        val html = RepoSources.text("app/src/main/assets/index.html")
        assertTrue("copy control needed", html.contains("data-action=\"copy\""))
        assertTrue("and its handler", html.contains("function copyMessage("))
        assertTrue("dispatched from the list", html.contains("action === 'copy'"))
    }

    @Test fun theTextComesFromStateNotFromMarkup() {
        val html = RepoSources.text("app/src/main/assets/index.html")
        val fn = html.substringAfter("function copyMessage(").substringBefore("function flashCopied(")
        // Reading textContent from the bubble would copy escaped entities.
        assertTrue(fn.contains("chatState.events"))
        assertFalse(fn.contains("textContent"))
    }

    @Test fun theClipboardIsReachableThroughTheBridge() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        // navigator.clipboard is not reliably available in a file:// WebView, so the native
        // clipboard is the dependable path.
        assertTrue(activity.contains("fun copyText("))
        assertTrue(activity.contains("ClipboardManager"))
        val html = RepoSources.text("app/src/main/assets/index.html")
        assertTrue(html.contains("call('copyText'"))
    }

    @Test fun copyingDoesNotDisturbTheMessageList() {
        val html = RepoSources.text("app/src/main/assets/index.html")
        val fn = html.substringAfter("function copyMessage(").substringBefore("function flashCopied(")
        // A re-render would drop the scroll position and the animation state.
        assertFalse(fn.contains("renderChat("))
        assertFalse(fn.contains("refreshChat("))
    }
}

/**
 * "EMOTION_DELTA отсутствует" — the chat prompt forbade all JSON, contradicting the emotion
 * section that offers EMOTION_DELTA. The model obeyed the stronger rule and stopped reporting.
 */
class EmotionDeltaReachableTest {
    @Test fun thePromptDoesNotForbidWhatItJustOffered() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        // A blanket "без JSON" silently cancelled the EMOTION_DELTA instruction above it.
        assertFalse("a blanket JSON ban must be gone",
            engine.contains("Отвечай обычной прозой, без JSON и без служебных блоков"))
        assertTrue("the exception must be stated",
            engine.contains("ИСКЛЮЧЕНИЯ, и только они"))
        assertTrue("and must include the emotion line", engine.contains("EMOTION_DELTA"))
    }

    @Test fun bothPromptsStillAdvertiseEmotionDelta() {
        val emotions = RepoSources.text("app/src/main/java/com/ruri/EmotionEngine.kt")
        assertTrue(emotions.contains("EMOTION_DELTA"))
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        assertTrue("chat prompt must include the emotion section",
            engine.contains("EmotionEngine.promptSection(root)"))
        val thinker = RepoSources.text("app/src/main/java/com/ruri/Thinker.kt")
        assertTrue("initiative prompt must include it too",
            thinker.contains("EmotionEngine.promptSection(root)"))
    }

    @Test fun theDeltaIsActuallyParsedAndApplied() {
        // Advertising a line that is never parsed would be the same lie in the other direction.
        for (file in listOf("ChatEngine.kt", "Thinker.kt")) {
            val src = RepoSources.text("app/src/main/java/com/ruri/" + file)
            assertTrue(file + " must parse EMOTION_DELTA",
                src.contains("EmotionEngine.parseAndStrip("))
            assertTrue(file + " must apply the delta",
                src.contains("EmotionEngine.applyDelta("))
        }
    }

    @Test fun theDeltaLineNeverReachesTheCommander() {
        val emotions = RepoSources.text("app/src/main/java/com/ruri/EmotionEngine.kt")
        val parser = emotions.substringAfter("fun parseAndStrip(").substringBefore("fun queryEmotions(")
        // The raw marker has to be stripped from the visible reply.
        assertTrue(parser.contains("reply.replace(m.value, \"\")"))
    }
}

/** Raw journal paging: a cursor instead of a full scan, and never a write. */
class RawLogTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-rawlog").toFile().also {
        FileManager.init(it)
    }

    private fun append(root: java.io.File, type: String, text: String, who: String = "commander") {
        FileManager.appendEvent(root, JSONObject()
            .put("type", type).put("who", who).put("text", text))
    }

    @Test fun pagesByCursorWithoutRescanning() {
        val dir = root()
        try {
            repeat(5) { append(dir, "message", "msg $it") }
            val (first, cursor) = RawLog.read(dir, afterOffset = 0L, limit = 2)
            assertEquals(2, first.size)
            assertTrue("cursor must advance past what was read", cursor > 0L)

            val (second, cursor2) = RawLog.read(dir, afterOffset = cursor, limit = 2)
            assertEquals(2, second.size)
            assertTrue("no overlap between pages", second.first().id != first.first().id)
            assertTrue(cursor2 > cursor)

            // Reading again from the same cursor returns the same page: paging is idempotent.
            val (again, _) = RawLog.read(dir, afterOffset = cursor, limit = 2)
            assertEquals(second.map { it.id }, again.map { it.id })
        } finally { dir.deleteRecursively() }
    }

    @Test fun filtersByType() {
        val dir = root()
        try {
            append(dir, "message", "hello")
            append(dir, "wake", "timer")
            append(dir, "tool_call", "read_document")
            val (messages, _) = RawLog.read(dir, types = setOf("message"))
            assertEquals(1, messages.size)
            assertEquals("message", messages.first().type)
        } finally { dir.deleteRecursively() }
    }

    @Test fun refusesToFabricateTheIndex() {
        val dir = root()
        try {
            append(dir, "message", "a")
            append(dir, "wake", "b")
            val index = RawLog.index(dir)
            assertEquals(2, index.optInt("lines"))
            assertEquals(1, index.getJSONObject("by_type").getJSONObject("message").optInt("count"))
            assertEquals(0, index.optInt("malformed"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun cyrillicSurvivesPaging() {
        val dir = root()
        try {
            append(dir, "message", "привет, как дела")
            val (entries, _) = RawLog.read(dir, limit = 5)
            assertEquals("привет, как дела", entries.first().json.optString("text"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theJournalIsNeverModifiedByReading() {
        val dir = root()
        try {
            append(dir, "message", "one")
            val before = File(dir, "memory/L0_raw.jsonl").readText()
            RawLog.read(dir, limit = 50)
            RawLog.index(dir)
            val after = File(dir, "memory/L0_raw.jsonl").readText()
            assertEquals("reading must not touch L0", before, after)
        } finally { dir.deleteRecursively() }
    }

    @Test fun inventedSourceIdsAreDropped() {
        val dir = root()
        try {
            append(dir, "message", "real event")
            val real = RawLog.read(dir, limit = 1).first.first().id
            val verified = Provenance.verifiedSources(dir, listOf(real, "evt-does-not-exist"))
            assertEquals(listOf(real), verified)
        } finally { dir.deleteRecursively() }
    }
}

/** Weight decides who survives a full store, and an eviction always leaves a record. */
class MemoryWeightTest {
    @Test fun recentAndConfirmedOutweighsOldAndSilent() {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val old = MemoryWeight.weight(now - 300 * day, now - 300 * day, confirmations = 0, origin = "diary", now = now)
        val fresh = MemoryWeight.weight(now - day, now - day, confirmations = 1, origin = "commander", now = now)
        assertTrue("a fresh Commander message must outrank a stale silent thought", fresh > old)
    }

    @Test fun confirmationRaisesWeight() {
        val now = System.currentTimeMillis()
        val once = MemoryWeight.weight(now, now, confirmations = 1, origin = "commander", now = now)
        val many = MemoryWeight.weight(now, now, confirmations = 10, origin = "commander", now = now)
        assertTrue("more confirmations must weigh more", many > once)
    }

    @Test fun evictionIsRecordedAndTheNewcomerSurvives() {
        val dir = Files.createTempDirectory("ruri-weight").toFile().also { FileManager.init(it) }
        try {
            val array = JSONArray()
            val now = System.currentTimeMillis()
            for (i in 0 until 3) {
                array.put(JSONObject().put("id", "old-$i").put("text", "stale $i")
                    .put("origin", "diary").put("created_at", now - (100 + i) * 86_400_000L)
                    .put("confirmations", 0))
            }
            val evicted = MemoryWeight.makeRoom(dir, "L1", array, cap = 3, incomingId = "new")
            assertEquals("exactly one slot must free up", 1, evicted.size)
            assertTrue("the weakest (oldest silent) entry goes", evicted.first().startsWith("old-"))

            // The eviction is journaled, so nothing disappears without a trace.
            val (events, _) = RawLog.read(dir, types = setOf("eviction_evt"))
            assertTrue("an eviction_evt must be written", events.isNotEmpty())
            assertEquals("L1", events.first().json.optString("store"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theIncomingClaimIsNeverTheOneEvicted() {
        val dir = Files.createTempDirectory("ruri-weight2").toFile().also { FileManager.init(it) }
        try {
            val array = JSONArray()
            val now = System.currentTimeMillis()
            // A very fresh, heavily confirmed store: the newcomer is the weakest thing in it.
            for (i in 0 until 2) {
                array.put(JSONObject().put("id", "keep-$i").put("text", "important $i")
                    .put("origin", "commander").put("created_at", now).put("confirmations", 50))
            }
            val evicted = MemoryWeight.makeRoom(dir, "L1", array, cap = 2, incomingId = "new")
            assertFalse("the new claim must not evict itself", evicted.contains("new"))
        } finally { dir.deleteRecursively() }
    }
}

/** Her own reading pass is bounded and resumes from a cursor. */
class ReflectionPassTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-reflect").toFile().also {
        FileManager.init(it)
        // A real event to cite.
        FileManager.appendEvent(it, JSONObject()
            .put("type", "message").put("who", "commander").put("text", "я люблю чай с молоком"))
    }

    @Test fun writesAtMostTheWriteBudget() {
        val dir = root()
        try {
            val candidates = (0 until 30).map {
                JSONObject().put("text", "кандидат номер $it про чай")
                    .put("source_ids", JSONArray())
            }
            var reads = 0
            val result = ReflectionPass.run(dir) { window ->
                reads = window.size
                candidates
            }
            assertTrue("must have read something", reads > 0)
            assertTrue("write budget must cap the pass", result.written <= ReflectionPass.WRITE_BUDGET)
            assertTrue("the rest is reported as skipped", result.skipped >= 0)
        } finally { dir.deleteRecursively() }
    }

    @Test fun aClaimWithoutSourcesIsAnAssumption() {
        val dir = root()
        try {
            ReflectionPass.run(dir) { _ ->
                listOf(JSONObject().put("text", "Командир любит чай с молоком").put("source_ids", JSONArray()))
            }
            val facts = JSONObject(FileManager.read(dir, "memory/L1_facts.json")).getJSONArray("facts")
            assertEquals(1, facts.length())
            assertEquals("assumption", facts.getJSONObject(0).optString("status"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aClaimWithARealSourceIsAsserted() {
        val dir = root()
        try {
            val realId = RawLog.read(dir, limit = 1).first.first().id
            ReflectionPass.run(dir) { _ ->
                listOf(JSONObject().put("text", "Командир пьёт чай с молоком")
                    .put("source_ids", JSONArray().put(realId)))
            }
            val facts = JSONObject(FileManager.read(dir, "memory/L1_facts.json")).getJSONArray("facts")
            assertEquals(1, facts.length())
            assertEquals("asserted", facts.getJSONObject(0).optString("status"))
            assertEquals(realId, facts.getJSONObject(0).getJSONArray("source_event_ids").optString(0))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theCursorAdvancesSoTheNextPassMovesOn() {
        val dir = root()
        try {
            val before = ReflectionPass.cursor(dir)
            ReflectionPass.run(dir) { emptyList() }
            val after = ReflectionPass.cursor(dir)
            assertTrue("cursor must move forward", after > before)
        } finally { dir.deleteRecursively() }
    }

    @Test fun repeatedClaimsStrengthenInsteadOfDuplicating() {
        val dir = root()
        try {
            val realId = RawLog.read(dir, limit = 1).first.first().id
            val candidate = { JSONObject().put("text", "Командир любит чай")
                .put("source_ids", JSONArray().put(realId)) }
            ReflectionPass.store(dir, candidate())
            ReflectionPass.store(dir, candidate())
            val facts = JSONObject(FileManager.read(dir, "memory/L1_facts.json")).getJSONArray("facts")
            assertEquals("no twin entries", 1, facts.length())
            assertTrue("confirmations must rise", facts.getJSONObject(0).optInt("confirmations") >= 2)
        } finally { dir.deleteRecursively() }
    }
}

/** The challenge trail: nothing is deleted, and rejected claims stay visible. */
class ProvenanceChallengeTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-prov").toFile().also {
        FileManager.init(it)
    }

    private fun seedFact(dir: java.io.File, id: String): JSONArray {
        val file = JSONObject()
        val facts = JSONArray().put(JSONObject().put("id", id).put("text", "спорный факт")
            .put("status", "asserted").put("source_event_ids", JSONArray().put("evt-1")))
        file.put("facts", facts)
        FileManager.write(dir, "memory/L1_facts.json", file.toString(2))
        return facts
    }

    @Test fun challengingMarksAndKeepsTheClaim() {
        val dir = root()
        try {
            seedFact(dir, "f-1")
            assertTrue(Provenance.challenge(dir, "L1", "f-1", "это неверно"))
            val facts = JSONObject(FileManager.read(dir, "memory/L1_facts.json")).getJSONArray("facts")
            assertEquals("claim must survive the challenge", 1, facts.length())
            assertEquals("challenged", facts.getJSONObject(0).optString("status"))
            assertEquals("это неверно", facts.getJSONObject(0).optString("challenge_reason"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectingKeepsSourcesAsTheRecordOfAMistake() {
        val dir = root()
        try {
            seedFact(dir, "f-2")
            assertTrue(Provenance.reject(dir, "L1", "f-2", "выдумка"))
            val facts = JSONObject(FileManager.read(dir, "memory/L1_facts.json")).getJSONArray("facts")
            val fact = facts.getJSONObject(0)
            assertEquals("rejected", fact.optString("status"))
            assertTrue("sources must remain", fact.getJSONArray("source_event_ids").length() == 1)
            assertTrue("rejection reason must remain", fact.optString("reject_reason").isNotBlank())
        } finally { dir.deleteRecursively() }
    }

    @Test fun aRejectedClaimCannotBeSilentlyRevived() {
        val dir = root()
        try {
            seedFact(dir, "f-3")
            Provenance.reject(dir, "L1", "f-3", "нет")
            // Remembering the same text again must not turn it back into a fact.
            val answer = ChatEngine.rememberFactForTest(dir, "спорный факт")
            assertTrue("must refuse to revive", answer.contains("отвергнут"))
            val facts = JSONObject(FileManager.read(dir, "memory/L1_facts.json")).getJSONArray("facts")
            assertEquals("rejected", facts.getJSONObject(0).optString("status"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theChallengeIsWrittenToTheJournal() {
        val dir = root()
        try {
            seedFact(dir, "f-4")
            Provenance.challenge(dir, "L1", "f-4")
            val (events, _) = RawLog.read(dir, types = setOf("claim_challenged"))
            assertTrue("challenge must be journaled", events.isNotEmpty())
            assertEquals("f-4", events.first().json.optString("claim_id"))
        } finally { dir.deleteRecursively() }
    }
}

/** The diary was only written when she chose "think", so it looked permanently empty. */
class DiaryWritesTest {
    @Test fun aChatTurnStillRecordsAThought() {
        val thinker = RepoSources.text("app/src/main/java/com/ruri/Thinker.kt")
        val chatBranch = thinker.substringAfter("if (publishesToChat(initiative, effective)) {")
            .substringBefore("} else {")
        // Previously the chat branch just sent the message and returned, leaving the diary alone.
        assertTrue("chat turns must also reach the diary", chatBranch.contains("Diary.append"))
    }

    @Test fun diaryEntriesCarryTheirSources() {
        val diary = RepoSources.text("app/src/main/java/com/ruri/Diary.kt")
        assertTrue("entries must record source ids", diary.contains("source_event_ids"))
        assertTrue("and a status", diary.contains("Provenance.statusFor"))
        val diaryLinked = diary.contains("diary_linked")
        assertTrue("the link is journaled too", diaryLinked)
    }

    @Test fun diaryEvictionIsRecorded() {
        val diary = RepoSources.text("app/src/main/java/com/ruri/Diary.kt")
        // The old code did entries.remove(0) with no record.
        assertFalse("silent drop must be gone", diary.contains("while (entries.length() > 500) entries.remove(0)"))
        assertTrue("weight decides instead", diary.contains("MemoryWeight.trim"))
    }
}

/** Bridge and UI for the insights page. */
class InsightsUiTest {
    @Test fun theBridgeExposesProvenance() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        for (method in listOf("listInsights", "readEventsByIds", "challengeClaim",
            "rejectClaim", "rawJournalIndex")) {
            assertTrue("bridge needs " + method, activity.contains("fun " + method + "("))
        }
    }

    @Test fun thePageShowsSourcesNotJustAVerdict() {
        val html = RepoSources.text("app/src/main/assets/index.html")
        assertTrue(html.contains("data-chip=\"INS\""))
        assertTrue(html.contains("function renderInsights("))
        // The Commander asked to see the full source list, not a summary.
        assertTrue(html.contains("source_event_ids"))
        assertTrue(html.contains("data-insight=\"sources\""))
        assertTrue(html.contains("data-insight=\"challenge\""))
    }

    @Test fun assumptionsAreMarkedAsSuch() {
        val html = RepoSources.text("app/src/main/assets/index.html")
        for (label in listOf("ПРЕДПОЛОЖЕНИЕ", "ОСПОРЕНО", "ОТВЕРГНУТО")) {
            assertTrue("status label missing: " + label, html.contains(label))
        }
    }
}

/**
 * The self-service machinery existed but was never announced, so she had no reason to use it.
 * A capability the model is not told about is not a capability.
 */
class SelfServiceAdvertisedTest {
    @Test fun sheIsToldSheCanReadHerOwnJournal() {
        val style = ChatStyle.memorySelfServiceSection()
        assertTrue("read_raw must be advertised", style.contains("read_raw"))
        assertTrue("with the type filter", style.contains("types"))
        assertTrue("and paging", style.contains("after_offset"))
        assertTrue("read-only must be stated", style.contains("только читается"))
    }

    @Test fun sheIsToldSheCanChallengeHerOwnClaims() {
        val style = ChatStyle.memorySelfServiceSection()
        assertTrue("challenge_claim must be advertised", style.contains("challenge_claim"))
        assertTrue("both stores named", style.contains("L1") && style.contains("L2"))
        assertTrue("nothing is deleted", style.contains("ничего не удаляет"))
    }

    @Test fun theChatPromptCarriesBothSections() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        assertTrue(engine.contains("ChatStyle.memorySelfServiceSection()"))
        assertTrue(engine.contains("ChatStyle.pendingInsightsSection(root)"))
    }

    @Test fun rememberFactDemandsItsSources() {
        val section = ChatStyle.architectureSection()
        assertTrue("source_ids must be required, not optional",
            section.contains("source_ids ОБЯЗАТЕЛЬНО"))
        assertTrue("the store checks them", section.contains("проверяются по журналу"))
        assertTrue("and the fallback is stated", section.contains("ПРЕДПОЛОЖЕНИЕ"))
    }

    @Test fun theToolSignatureAcceptsWhatThePromptAsksFor() {
        // Advertising a field the parser rejects would make every call fail silently.
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        assertTrue(engine.contains("\"remember_fact\" -> setOf(\"action\", \"text\", \"source_ids\", \"confidence\")"))
    }

    @Test fun sheCanSeeHerOwnUnverifiedClaims() {
        val dir = Files.createTempDirectory("ruri-pending").toFile().also { FileManager.init(it) }
        try {
            val file = JSONObject()
            file.put("facts", JSONArray()
                .put(JSONObject().put("id", "f-a").put("text", "Командир любит кофе")
                    .put("status", "assumption"))
                .put(JSONObject().put("id", "f-b").put("text", "Командир любит чай")
                    .put("status", "challenged"))
                .put(JSONObject().put("id", "f-c").put("text", "Командир программист")
                    .put("status", "asserted")))
            FileManager.write(dir, "memory/L1_facts.json", file.toString(2))

            val section = ChatStyle.pendingInsightsSection(dir)
            assertTrue("assumptions must be surfaced", section.contains("Командир любит кофе"))
            assertTrue("challenged claims too", section.contains("Командир любит чай"))
            assertTrue("confirmed facts need no warning", !section.contains("Командир программист"))
            assertTrue("and the limit is explained", section.contains("НЕ факты"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun thePendingSectionIsSilentWhenNothingIsUnverified() {
        val dir = Files.createTempDirectory("ruri-pending2").toFile().also { FileManager.init(it) }
        try {
            assertEquals("", ChatStyle.pendingInsightsSection(dir))
        } finally { dir.deleteRecursively() }
    }
}

/** The reflection pass discarded its own result. */
class ReflectionResultTest {
    @Test fun thePassResultIsReturnedNotNulled() {
        val runner = RepoSources.text("app/src/main/java/com/ruri/ReflectionRunner.kt")
        // `lastResult = null` inside the lambda wiped the value that was about to be returned.
        assertFalse("must not null its own result", runner.contains("lastResult = null"))
        assertTrue("must capture the pass result", runner.contains("val result = ReflectionPass.run"))
        assertTrue("and return it", runner.contains("return result"))
    }
}

/**
 * The raw journal window filled up with bookkeeping: 11 of 18 readable types were machinery, so
 * a 30-entry page never reached the conversation.
 */
class RawLogDefaultsTest {
    @Test fun theDefaultWindowIsLifeNotMachinery() {
        // The default filter must be the conversational set, not everything readable.
        assertTrue("a conversational subset must exist", RawLog.CONVERSATIONAL_TYPES.isNotEmpty())
        assertTrue("messages belong in it", "message" in RawLog.CONVERSATIONAL_TYPES)
        assertTrue("diary links too", "diary_linked" in RawLog.CONVERSATIONAL_TYPES)
        for (technical in listOf("tool_call", "tool_result", "wake", "emotion_delta",
            "eviction_evt", "initiative_gate_evaluated")) {
            assertFalse("$technical must not be default",
                technical in RawLog.CONVERSATIONAL_TYPES)
        }
    }

    @Test fun technicalTypesStayRequestable() {
        // She still needs them sometimes; they must be allowed, just not default.
        assertTrue("tool_call" in RawLog.READABLE_TYPES)
        assertTrue("wake" in RawLog.READABLE_TYPES)
        assertTrue(RawLog.TECHNICAL_TYPES.isNotEmpty())
    }

    @Test fun aTechnicalFloodDoesNotHideTheConversation() {
        val dir = Files.createTempDirectory("ruri-rawdefaults").toFile().also { FileManager.init(it) }
        try {
            val messageId = JSONObject(FileManager.appendEvent(dir, JSONObject()
                .put("type", "message").put("who", "commander").put("text", "важное сообщение")))
                .optString("id")
            // Bury it in technical noise, the way a real day does.
            repeat(60) {
                FileManager.appendEvent(dir, JSONObject()
                    .put("type", "tool_call").put("action", "read_document"))
                FileManager.appendEvent(dir, JSONObject().put("type", "wake").put("reason", "timer"))
                FileManager.appendEvent(dir, JSONObject()
                    .put("type", "emotion_delta").put("delta", JSONObject().put("joy", 1)))
            }
            // A default page must still contain the message.
            val (entries, _) = RawLog.read(dir, limit = 10)
            assertTrue("the conversation must survive the noise",
                entries.any { it.id == messageId })
            assertTrue("and no machinery should appear",
                entries.none { it.type == "tool_call" || it.type == "wake" })
        } finally { dir.deleteRecursively() }
    }

    @Test fun technicalEventsRemainReachableOnRequest() {
        val dir = Files.createTempDirectory("ruri-rawtech").toFile().also { FileManager.init(it) }
        try {
            FileManager.appendEvent(dir, JSONObject().put("type", "tool_call").put("action", "x"))
            val (entries, _) = RawLog.read(dir, types = setOf("tool_call"))
            assertEquals(1, entries.size)
        } finally { dir.deleteRecursively() }
    }
}

/** Search must cover the whole corpus, not a fixed recent slice. */
class FullSearchTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-search").toFile().also {
        FileManager.init(it)
    }

    private fun say(root: java.io.File, who: String, text: String) {
        FileManager.appendEvent(root, JSONObject()
            .put("type", "message").put("who", who).put("text", text))
    }

    @Test fun findsConversationScopedToMessages() {
        val dir = root()
        try {
            say(dir, "commander", "я планирую поездку в Токио весной")
            say(dir, "ruri", "звучит здорово")
            val out = MemoryRetriever.search(dir, "Токио", MemoryRetriever.SCOPE_MESSAGES)
            assertTrue("the message must be found", out.contains("Токио"))
            assertTrue("scope is reported", out.contains("messages"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun scopeFactsExcludesConversation() {
        val dir = root()
        try {
            say(dir, "commander", "я люблю крепкий кофе без сахара")
            val facts = JSONObject().put("facts", JSONArray().put(JSONObject()
                .put("id", "f1").put("text", "Командир любит кофе").put("confidence", 0.8)
                .put("created_at", System.currentTimeMillis()).put("status", "asserted")))
            FileManager.write(dir, "memory/L1_facts.json", facts.toString(2))

            val onlyFacts = MemoryRetriever.search(dir, "кофе", MemoryRetriever.SCOPE_FACTS)
            assertTrue("fact found", onlyFacts.contains("Командир любит кофе"))
            assertFalse("conversation excluded", onlyFacts.contains("без сахара"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun searchReachesOldEventsBeyondThePromptWindow() {
        val dir = root()
        try {
            say(dir, "commander", "запомни: пароль от склада выдаёт Борис")
            // Bury it under far more history than the prompt ever carries.
            repeat(400) { say(dir, "commander", "болтовня номер $it") }
            val out = MemoryRetriever.search(dir, "Борис", MemoryRetriever.SCOPE_MESSAGES)
            assertTrue("old events must stay searchable", out.contains("Борис"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectedClaimsAreNotSearchableEvidence() {
        val dir = root()
        try {
            val facts = JSONObject().put("facts", JSONArray().put(JSONObject()
                .put("id", "bad").put("text", "Командир ненавидит кофе")
                .put("status", "rejected").put("created_at", System.currentTimeMillis())))
            FileManager.write(dir, "memory/L1_facts.json", facts.toString(2))
            val out = MemoryRetriever.search(dir, "кофе", MemoryRetriever.SCOPE_FACTS)
            assertFalse("a rejected claim must not resurface as evidence",
                out.contains("ненавидит"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theResultLimitIsGenerous() {
        val dir = root()
        try {
            repeat(40) { say(dir, "commander", "поездка в Токио вариант $it") }
            val out = MemoryRetriever.search(dir, "Токио", MemoryRetriever.SCOPE_MESSAGES, limit = 30)
            val lines = out.lines().count { it.startsWith("- [") }
            assertTrue("must return more than the old five", lines > 5)
            assertTrue("but respect the requested limit", lines <= 30)
        } finally { dir.deleteRecursively() }
    }

    @Test fun recallAndSearchShareOneRanking() {
        val reader = RepoSources.text("app/src/main/java/com/ruri/MemoryRetriever.kt")
        // Two copies of the scoring logic drifted apart before; recall now delegates.
        assertTrue("recall must reuse collect",
            reader.contains("val hits = collect(root, query, SCOPE_ALL, fullScan = false)"))
        assertTrue("search must exist", reader.contains("fun search("))
    }

    @Test fun sheIsToldAboutSearchAndScopes() {
        val style = ChatStyle.memorySelfServiceSection()
        assertTrue("search advertised", style.contains("recall"))
        for (scope in listOf("messages", "facts", "diary", "notes")) {
            assertTrue("scope advertised: $scope", style.contains(scope))
        }
        assertTrue("and the default journal excludes machinery",
            style.contains("НЕ попадают"))
    }
}

/**
 * She never asked for page N+1, so long documents were never actually read.
 *
 * Two causes: the continuation marker lived at the end of a 6000-character page but results were
 * cut at 4000, so the marker was always sliced off; and paging relied on her remembering a
 * protocol instead of the app doing it.
 */
class DocumentPaginationTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-paging").toFile().also {
        FileManager.init(it)
    }

    @Test fun theResultCapExceedsAPageSoMarkersSurvive() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        val cap = Regex("TOOL_RESULT_LIMIT = (\\d+)").find(engine)?.groupValues?.get(1)?.toInt()
        val page = Regex("PAGE_CHARS = (\\d+)")
            .find(RepoSources.text("app/src/main/java/com/ruri/DocReader.kt"))
            ?.groupValues?.get(1)?.toInt()
        assertNotNull(cap)
        assertNotNull(page)
        assertTrue("cutting below a page guarantees losing the marker", cap!! > page!!)
    }

    @Test fun theMarkerSurvivesTruncation() {
        val body = "x".repeat(20_000) + "\n[ПРОДОЛЖЕНИЕ: вызови read_document с name=\"doc\" и page=3]"
        val trimmed = ChatEngine.trimKeepingMarker(body, limit = 1000)
        assertTrue("the next page must remain visible", trimmed.contains("page=3"))
        assertTrue("and truncation must be admitted", trimmed.contains("ТЕКСТ ОБРЕЗАН"))
        assertTrue("not longer than the limit by much", trimmed.length < 1400)
    }

    @Test fun theEndOfDocumentMarkerAlsoSurvives() {
        val body = "y".repeat(20_000) + "\n[КОНЕЦ ДОКУМЕНТА]"
        val trimmed = ChatEngine.trimKeepingMarker(body, limit = 800)
        assertTrue(trimmed.contains("КОНЕЦ ДОКУМЕНТА"))
    }

    @Test fun nothingIsAlteredWhenItFits() {
        val small = "короткий текст\n[КОНЕЦ ДОКУМЕНТА]"
        assertEquals(small, ChatEngine.trimKeepingMarker(small, limit = 9000))
    }

    @Test fun aLongDocumentIsReadInOneCall() {
        val dir = root()
        try {
            // ~3 pages worth of text at PAGE_CHARS = 6000.
            val paragraph = "Это предложение про проект маяка. ".repeat(60)
            val content = (1..12).joinToString("\n\n") { "$paragraph (часть $it)" }
            DocReader.store(dir, "long.txt", content.toByteArray(Charsets.UTF_8))

            val (text, pages) = ChatEngine.readWholeDocument(dir, "long.txt")
            assertTrue("must read beyond the first page", pages > 1)
            assertTrue("text must be returned", text.length > 6000)
            assertTrue("it must say the document is complete", text.contains("ДОКУМЕНТ ПРОЧИТАН"))
            assertTrue("content from the start is present", text.contains("проект маяка"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theAutomaticReadIsBounded() {
        val dir = root()
        try {
            // Far more than the page budget can hold, so the cap must engage.
            val huge = "слово ".repeat(400_000)
            DocReader.store(dir, "huge.txt", huge.toByteArray(Charsets.UTF_8))
            val (text, pages) = ChatEngine.readWholeDocument(dir, "huge.txt")
            assertTrue("must stop instead of loading everything", pages <= 25)
            assertTrue("and say so", text.contains("дальше текст слишком велик"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aMissingDocumentFailsHonestly() {
        val dir = root()
        try {
            val (text, pages) = ChatEngine.readWholeDocument(dir, "нет-такого.txt")
            assertEquals(0, pages)
            assertTrue("must explain the failure", text.contains("не найден"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun sheIsNoLongerToldToPageManually() {
        val style = ChatStyle.documentHint("прочитай документ", listOf("doc.txt"))
        assertTrue("whole-document read must be stated", style.contains("читается ЦЕЛИКОМ"))
        assertTrue("page is optional now", style.contains("страницу указывать не нужно"))
        assertFalse("must not instruct manual paging",
            style.contains("вызови read_document с этим page"))
    }
}

/**
 * "не удалось завершить цепочку действий: достигнут лимит 8 вызовов" — asked to find every
 * mention of her appearance, she looped because her own description was not searchable at all.
 */
class SelfDescriptionSearchTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-self").toFile().also {
        FileManager.init(it)
    }

    @Test fun appearanceIsSearchable() {
        val dir = root()
        try {
            val appearance = JSONObject()
                .put("description", "невысокая, тёмные волосы до плеч, серые глаза")
                .put("style", "носит свободный свитер")
            FileManager.write(dir, "core/appearance.json", appearance.toString(2))

            val out = MemoryRetriever.search(dir, "волосы", MemoryRetriever.SCOPE_SELF)
            assertTrue("appearance must be found", out.contains("волосы"))
            assertTrue("and labelled as appearance", out.contains("внешность"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun characterIsSearchable() {
        val dir = root()
        try {
            val character = JSONObject().put("speech_rules", "говорю прямо и коротко")
            FileManager.write(dir, "core/character.json", character.toString(2))
            val out = MemoryRetriever.search(dir, "говорю", MemoryRetriever.SCOPE_SELF)
            assertTrue(out.contains("говорю прямо"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theSelfScopeIsIncludedInAll() {
        val dir = root()
        try {
            FileManager.write(dir, "core/appearance.json",
                JSONObject().put("description", "рыжие волосы").toString(2))
            val out = MemoryRetriever.search(dir, "волосы", MemoryRetriever.SCOPE_ALL)
            assertTrue("a general search must reach her self-description too",
                out.contains("рыжие волосы"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun nestedAppearanceFieldsAreSearchable() {
        val dir = root()
        try {
            val appearance = JSONObject().put("lora", JSONObject().put("style", "акварельный стиль"))
            FileManager.write(dir, "core/appearance.json", appearance.toString(2))
            val out = MemoryRetriever.search(dir, "акварельный", MemoryRetriever.SCOPE_SELF)
            assertTrue("nested fields must not be skipped", out.contains("акварельный"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theScopeIsOfferedToHer() {
        val style = ChatStyle.memorySelfServiceSection()
        assertTrue("self scope advertised", style.contains("\"self\""))
        assertTrue("and explained", style.contains("внешности"))
        assertTrue("she is told one search is enough", style.contains("ОДНОГО ПОИСКА"))
    }
}

/** Hitting the hop limit must degrade gracefully, never replace the answer with an error. */
class ToolHopLimitTest {
    @Test fun theLimitDoesNotDiscardGatheredMaterial() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        // The old code returned a bare system message, throwing away every tool result.
        assertTrue("must ask for a closing answer",
            engine.contains("ЛИМИТ ДЕЙСТВИЙ ИСЧЕРПАН"))
        assertTrue("with an instruction to admit gaps",
            engine.contains("осталось непроверенным"))
        assertTrue("and salvage the reply", engine.contains("salvaged"))
    }

    @Test fun theErrorTextSurvivesOnlyAsLastResort() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        val block = engine.substringAfter("if (leftover.isNotEmpty() && hops >= MAX_TOOL_HOPS) {")
            .substringBefore("val (cleaned, diaryAgain, leftover)")
        // The bare limit message may still exist after the salvage attempt, but the salvage
        // must come first.
        val salvageIndex = block.indexOf("salvaged.isNotBlank()")
        assertTrue("salvage must be attempted first", salvageIndex > 0)
    }
}

/**
 * The memory layers were almost always empty because writing was rationed by thresholds.
 * L1 must record every new fact; L2 must record the act of thinking.
 */
class NoMemoryThresholdsTest {
    @Test fun factsNoLongerWaitForAQuorumOfThreeMessages() {
        val extractor = RepoSources.text("app/src/main/java/com/ruri/FactExtractor.kt")
        // A single remark is often all the Commander says in a day; it must still be remembered.
        assertFalse("the three-message quorum must be gone",
            extractor.contains("commanderIds.size < MIN_COMMANDER_MESSAGES"))
        assertTrue("but an empty batch is still a no-op", extractor.contains("commanderIds.isEmpty()"))
    }

    @Test fun theCooldownOnlyAppliesAfterAFailure() {
        val extractor = RepoSources.text("app/src/main/java/com/ruri/FactExtractor.kt")
        // Rationing normal traffic behind a cooldown is what kept L1 silent.
        assertTrue("cooldown must be conditional", extractor.contains("lastAttemptFailed"))
        assertTrue("and the flag recorded", extractor.contains("last_extraction_failed"))
    }

    @Test fun thinkingIsNotGatedOnEmotionalPressure() {
        val dir = Files.createTempDirectory("ruri-gate").toFile().also {
            FileManager.init(it)
            EmotionEngine.ensure(it)
        }
        try {
            // A calm moment: pressure far below the old threshold of 18.
            val allowed = EmotionEngine.thoughtGate(dir, System.currentTimeMillis(), 0L)
            assertTrue("a calm thought must still be allowed", allowed)
        } finally { dir.deleteRecursively() }
    }

    @Test fun theAntiSpamGapStillProtectsAgainstDuplicates() {
        val dir = Files.createTempDirectory("ruri-gate2").toFile().also {
            FileManager.init(it)
            EmotionEngine.ensure(it)
        }
        try {
            val now = System.currentTimeMillis()
            // Immediately after a thought turn the gate must refuse: same thought, same moment.
            assertFalse(EmotionEngine.thoughtGate(dir, now, now - 1_000L))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theDiaryIsDescribedAsAThinkingLog() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/EmotionEngine.kt")
        assertTrue("the intent must be stated", engine.contains("thinking log"))
        assertTrue("pressure shapes content, not permission",
            engine.contains("never *whether* she writes") || engine.contains("whether"))
    }
}

/** Her body: the schema exists and reaches both prompts. */
class AppearanceTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-look").toFile().also {
        FileManager.init(it)
    }

    @Test fun theSchemaHasRealFields() {
        // The description now lives in AppearanceSeed; FileManager only wires it in.
        val seed = AppearanceSeed.content()
        for (field in listOf("hair", "eyes", "clothing", "voice", "mannerisms", "height")) {
            assertTrue("appearance needs $field", seed.contains("\"$field\""))
        }
        assertTrue("and a history for changes", seed.contains("\"history\""))
    }

    @Test fun filledFieldsReachThePrompt() {
        val dir = root()
        try {
            val appearance = JSONObject()
                .put("hair", "тёмные, до плеч")
                .put("eyes", "серые")
            FileManager.write(dir, "core/appearance.json", appearance.toString(2))
            val section = ChatStyle.appearanceSection(dir)
            assertTrue(section.contains("тёмные, до плеч"))
            assertTrue(section.contains("серые"))
            assertTrue("uses readable labels", section.contains("волосы"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun anEmptyAppearanceIsAdmittedNotInvented() {
        val dir = root()
        try {
            // init now seeds her described body, so an undescribed state must be created
            // explicitly: this checks the "nothing filled in" branch.
            FileManager.write(dir, "core/appearance.json", JSONObject().put("schema_version", 3).toString())
            val section = ChatStyle.appearanceSection(dir)
            assertTrue("must say it is undefined", section.contains("НЕ ОПИСАНА"))
            assertTrue("and forbid making one up", section.contains("Не выдумывай"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun bothPromptsKnowHerBody() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        assertTrue("chat must include it", engine.contains("ChatStyle.appearanceForMode(root)"))
        val thinker = RepoSources.text("app/src/main/java/com/ruri/Thinker.kt")
        assertTrue("initiative must include it", thinker.contains("ChatStyle.appearanceForMode(root)"))
    }
}

/**
 * "Commander message not found in L0" fired on the first attempt and the retry worked, because
 * the lookup scanned a fixed window that technical noise had already pushed the message out of.
 */
class CommanderMessageLookupTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-lookup").toFile().also {
        FileManager.init(it)
    }

    @Test fun aMessageOutsideTheFastWindowIsStillFound() {
        val dir = root()
        try {
            val id = JSONObject(FileManager.appendEvent(dir, JSONObject()
                .put("type", "message").put("who", "commander").put("text", "важное сообщение")))
                .optString("id")
            // Bury it under far more than the quick window holds, the way a busy day does.
            repeat(6000) {
                FileManager.appendEvent(dir, JSONObject()
                    .put("type", "tool_call").put("action", "read_document"))
            }
            // The fast window cannot see it...
            val quick = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
            assertTrue("a direct lookup must exist", quick.contains("fun findEventById("))
            // ...but the direct lookup must.
            val direct = ChatEngine.findEventById(dir, id)
            assertNotNull("the message must be found by id", direct)
            assertEquals("важное сообщение", direct!!.optString("text"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun identificationUsesTheDirectLookupAsFallback() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        val fn = engine.substringAfter("internal fun isCommanderMessage(")
            .substringBefore("internal fun setTurnInFlight(")
        // The preflight used to answer "not found" purely from a fixed window.
        assertTrue("must fall back to the id lookup", fn.contains("findEventById(root, messageId)"))
        assertTrue("and still verify the role", fn.contains("\"commander\""))
    }

    @Test fun deletedMessagesAreStillRefused() {
        val dir = root()
        try {
            val id = JSONObject(FileManager.appendEvent(dir, JSONObject()
                .put("type", "message").put("who", "commander").put("text", "удалённое")))
                .optString("id")
            assertTrue(ChatEngine.isCommanderMessage(dir, id))
            Tombstone.add(dir, id)
            assertFalse("a tombstoned message must not be answered",
                ChatEngine.isCommanderMessage(dir, id))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aRuriMessageIsNotMistakenForACommanderOne() {
        val dir = root()
        try {
            val id = JSONObject(FileManager.appendEvent(dir, JSONObject()
                .put("type", "message").put("who", "ruri").put("text", "мой ответ")))
                .optString("id")
            assertFalse(ChatEngine.isCommanderMessage(dir, id))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theHistoryWindowIsLargeEnoughForABusyDay() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        val window = Regex("maxLines = (\\d+), maxBytes = 8 \\* 1024 \\* 1024")
            .find(engine)?.groupValues?.get(1)?.toInt()
        assertNotNull("history window must be explicit", window)
        assertTrue("a few thousand lines is not enough for a busy day", window!! >= 10000)
    }
}

/**
 * The Commander described her body in full. Two things must hold: no detail is lost, and the
 * explicit half stays out of ordinary chat.
 */
class AppearanceSeedTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-look2").toFile().also {
        FileManager.init(it)
    }

    @Test fun everyDescribedDetailIsPresent() {
        // Case-insensitive: a detail is not lost just because it starts a sentence.
        val seed = AppearanceSeed.content().lowercase()
        for (detail in listOf(
            "перевёрнутая восьмёрка", "170 см", "voluptuous",
            "6 размер", "соски", "складка-тень",
            "талия", "живот", "пресса",
            "бёдра", "задница",
            "ляжки", "икры", "щиколотки",
            "сердечком", "матка", "яичник", "светящиеся", "розов",
            "смазк", "имплант", "подмышк", "горошину", "ниткой", "ванил"
        )) {
            assertTrue("detail lost from the seed: $detail", seed.contains(detail))
        }
    }

    @Test fun theFaceFollowsTheRequestedStyle() {
        val seed = AppearanceSeed.content()
        assertTrue("must be kuro-gyaru / ganguro", seed.contains("gyaru") || seed.contains("ganguro"))
        // The defining attributes of that style, not just the name.
        for (attribute in listOf("загар", "обесцвечен", "подводка", "ресниц", "губы")) {
            assertTrue("style attribute missing: $attribute", seed.contains(attribute))
        }
    }

    @Test fun fieldsAreSeparateNotOneBlob() {
        val file = JSONObject(AppearanceSeed.content())
        // Discreet fields are what let her change one thing without rewriting all of herself.
        for (field in listOf("bust", "waist", "hips", "legs", "pubic", "intimate",
            "underarms", "figure", "face", "skin")) {
            assertTrue("missing field $field", file.has(field))
            assertTrue("field $field must not be empty", file.optString(field).isNotBlank())
        }
    }

    @Test fun aBlankFileIsSeededButRealContentIsKept() {
        val dir = root()
        try {
            // Blank: gets the Commander's description.
            FileManager.write(dir, "core/appearance.json", "{\"description\":\"\"}")
            assertTrue(AppearanceSeed.isBlank(FileManager.read(dir, "core/appearance.json")))

            // Written by hand: must be treated as real content.
            val mine = JSONObject().put("description", "моё собственное описание")
            assertFalse(AppearanceSeed.isBlank(mine.toString()))
        } finally { dir.deleteRecursively() }
    }

    @Test fun metadataAloneDoesNotCountAsContent() {
        // A file holding only schema_version and lora placeholders is still undescribed.
        val meta = JSONObject()
            .put("schema_version", 3)
            .put("seed", 424242)
            .put("lora", JSONObject().put("trigger", "ruri_ru_r1"))
        assertTrue("metadata must not block the seed", AppearanceSeed.isBlank(meta.toString()))
    }
}

/** Tier 1 always, tier 2 only in role-play. */
class AppearanceTiersTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-tier").toFile().also {
        FileManager.init(it)
    }

    @Test fun ordinaryChatGetsTheSilhouetteOnly() {
        val dir = root()
        try {
            ChatStyle.setRoleplay(dir, false)
            val section = ChatStyle.appearanceForMode(dir)
            assertTrue("silhouette must be present", section.contains("ТВОЯ ВНЕШНОСТЬ"))
            assertTrue(section.contains("170"))
            // The explicit half must not appear unprompted.
            assertFalse("anatomy must stay out of messenger mode",
                section.contains("интимные особенности"))
            assertFalse(section.contains("подмышки и запах"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun roleplayGetsTheFullBody() {
        val dir = root()
        try {
            ChatStyle.setRoleplay(dir, true)
            val section = ChatStyle.appearanceForMode(dir)
            assertTrue("silhouette stays", section.contains("ТВОЯ ВНЕШНОСТЬ"))
            assertTrue("and the detail is added", section.contains("ТВОЁ ТЕЛО ПОДРОБНО"))
            assertTrue(section.contains("интимные особенности"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun bothPromptsUseTheModeAwareForm() {
        // Deciding the tiers at each call site is how they drift apart.
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        assertTrue(engine.contains("ChatStyle.appearanceForMode(root)"))
        val thinker = RepoSources.text("app/src/main/java/com/ruri/Thinker.kt")
        assertTrue(thinker.contains("ChatStyle.appearanceForMode(root)"))
    }

    @Test fun anUndescribedBodyIsStillAdmittedNotInvented() {
        val dir = Files.createTempDirectory("ruri-tier2").toFile().also {
            FileManager.init(it)
        }
        try {
            FileManager.write(dir, "core/appearance.json",
                JSONObject().put("schema_version", 3).toString())
            assertTrue(ChatStyle.appearanceSection(dir).contains("НЕ ОПИСАНА"))
            assertEquals("", ChatStyle.explicitAppearanceSection(dir))
        } finally { dir.deleteRecursively() }
    }
}

/**
 * She must work always. Each test here pins one of the ways she could stop.
 */
class AlwaysWorkingTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-always").toFile().also {
        FileManager.init(it)
        EmotionEngine.ensure(it)
    }

    @Test fun aKilledTurnDoesNotSilenceHerForever() {
        val dir = root()
        try {
            val now = System.currentTimeMillis()
            // A turn that started and never finished, as happens when the process is killed.
            FileManager.updateL4State(dir) {
                it.put("turn_in_flight", true).put("turn_in_flight_at", now - 60_000L)
            }
            assertTrue("a recent turn is genuinely in flight",
                ChatEngine.turnInFlight(dir, now))

            FileManager.updateL4State(dir) {
                it.put("turn_in_flight_at", now - 11L * 60L * 1000L)
            }
            assertFalse("an ancient flag must expire", ChatEngine.turnInFlight(dir, now))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aFlagFromAnOlderBuildIsIgnored() {
        val dir = root()
        try {
            // No timestamp at all: debris, not a running turn.
            FileManager.updateL4State(dir) { it.put("turn_in_flight", true) }
            assertFalse(ChatEngine.turnInFlight(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun thinkingIsAllowedEvenWhenNobodyHasEverWritten() {
        val dir = root()
        try {
            // last_seen_commander is 0 on a fresh install; the initiative pressure must still grow.
            val state = JSONObject(FileManager.read(dir, "memory/L4_state.json"))
            assertEquals(0L, state.optLong("last_seen_commander", 0L))
            EmotionEngine.tick(dir, System.currentTimeMillis() + 6L * 3600L * 1000L, 0L,
                System.currentTimeMillis())
            val values = EmotionEngine.readValues(dir)
            assertTrue("boredom must grow without a prior reply",
                values.optDouble("boredom", 0.0) > 20.0)
            assertTrue("so must longing",
                values.optDouble("longing", 0.0) > 20.0)
        } finally { dir.deleteRecursively() }
    }

    @Test fun aParkedMessageDoesNotSuspendHer() {
        val dir = root()
        try {
            val id = JSONObject(FileManager.appendEvent(dir, JSONObject()
                .put("type", "message").put("who", "commander").put("text", "вопрос"))).optString("id")
            CommanderReplyQueue.enqueue(dir, id)
            // Terminal failure: the item waits for the «ПОВТОРИТЬ» button.
            CommanderReplyQueue.recordFailure(dir, id, "no_vpn")

            assertTrue("it is still pending", CommanderReplyQueue.hasPending(dir))
            assertFalse("but nothing will be retried automatically",
                CommanderReplyQueue.hasAutoRetryPending(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun anOrdinaryPendingMessageStillCounts() {
        val dir = root()
        try {
            val id = JSONObject(FileManager.appendEvent(dir, JSONObject()
                .put("type", "message").put("who", "commander").put("text", "вопрос"))).optString("id")
            CommanderReplyQueue.enqueue(dir, id)
            // A transient failure schedules another attempt, so this one does hold priority.
            CommanderReplyQueue.recordFailure(dir, id, "timeout")
            assertTrue(CommanderReplyQueue.hasAutoRetryPending(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theAutonomyBudgetIsNotEatenByTheCommanderChat() {
        val dir = root()
        try {
            // A busy day of ordinary chat traffic.
            repeat(180) { RouteMeta.recordAttempt(dir, Route.ID_ROUTEWAY) }
            assertTrue("his conversation must not exhaust her allowance",
                RouteMeta.autonomyBudgetAvailable(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun herOwnCyclesDoCountAgainstHerBudget() {
        val dir = root()
        try {
            repeat(200) { RouteMeta.recordAutonomousAttempt(dir, Route.ID_ROUTEWAY) }
            assertFalse("a runaway loop must still be bounded",
                RouteMeta.autonomyBudgetAvailable(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun noScreenGateStopsTheRetry() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        val turn = activity.substringAfter("private fun attemptPendingTurn()")
            .substringBefore("private fun scheduleRetryIfNeeded(")
        // The Commander's rule is that she works regardless of the screen.
        assertFalse("the screen must not gate the turn", turn.contains("if (!activityResumed) return"))
        val retry = activity.substringAfter("private fun scheduleRetryIfNeeded(")
            .substringBefore("private fun errorPayload(")
        assertFalse("nor the retry timer", retry.contains("if (!activityResumed) return"))
    }

    @Test fun theNetworkCallbackSurvivesOneOwnerLeaving() {
        val net = RepoSources.text("app/src/main/java/com/ruri/NetState.kt")
        // The activity used to tear down the callback the foreground service still needed.
        assertTrue("owners must be counted", net.contains("private var owners = 0"))
        assertTrue("and checked before teardown", net.contains("if (owners > 0) return"))
    }

    @Test fun thePromptNamesTheToolsItAdvertises() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        // A blanket ban on tool JSON silently disabled every tool, exactly as it once did for
        // EMOTION_DELTA.
        assertTrue("the exception must allow the advertised actions",
            engine.contains("действия, перечисленные выше в этом сообщении"))
        assertFalse("no blanket ban may remain",
            engine.contains("без JSON-объектов инструментов. \" +"))
    }
}

/** Batch 2: two dead features, journal protection, no silent wipe. */
class BrokenFeatureRepairTest {
    @Test fun imageAttachmentUsesTheRealBridge() {
        val html = RepoSources.text("app/src/main/assets/index.html")
        val fn = html.substringAfter("function uploadImage(file){").substringBefore("function ")
        // appendLog() does not exist; calling it threw ReferenceError on every attachment.
        assertFalse("the phantom function must be gone", fn.contains("appendLog("))
        assertTrue("it must write through the bridge", fn.contains("call('appendEvent'"))
        assertTrue("and report a failure instead of failing silently", fn.contains("alert("))
    }

    @Test fun exportWritesWhereFileProviderCanServeIt() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        val exports = activity.substringAfter("fun exportArchive(")
            .substringBefore("runOnUiThread")
        // cacheDir is not a published root, so getUriForFile threw and export never worked.
        assertFalse("must not write into cacheDir", exports.contains("File(cacheDir"))
        assertTrue("must write under files/backup/exports", exports.contains("File(filesDir, \"backup/exports\")"))
        val paths = RepoSources.text("app/src/main/res/xml/filepaths.xml")
        assertTrue("and the provider must publish exactly that path",
            paths.contains("path=\"backup/exports/\""))
    }

    @Test fun anArchiveWithoutTheJournalIsRefused() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        val import = activity.substringAfter("fun importArchive(").substringBefore("fun exportArchive(")
        // Restore deletes live memory files the archive lacks, so a missing L0 means data loss.
        assertTrue("L0 must be required", import.contains("archive.has(\"memory/L0_raw.jsonl\")"))
        assertTrue("with an explanation", import.contains("не принимается"))
    }
}

/** The append-only journal must be unreachable by every route, including path aliases. */
class JournalProtectionTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-l0guard").toFile().also {
        FileManager.init(it)
    }

    @Test fun noAliasCanRewriteTheJournal() {
        val dir = root()
        try {
            FileManager.appendEvent(dir, JSONObject()
                .put("type", "message").put("who", "commander").put("text", "живое событие"))
            val before = java.io.File(dir, "memory/L0_raw.jsonl").readText()

            // Each of these normalises onto the real journal.
            for (alias in listOf(
                "memory/L0_raw.jsonl",
                "memory//L0_raw.jsonl",
                "memory/./L0_raw.jsonl",
                "./memory/L0_raw.jsonl"
            )) {
                val threw = runCatching { FileManager.write(dir, alias, "") }.isFailure
                assertTrue("write via [$alias] must be refused", threw)
            }

            val after = java.io.File(dir, "memory/L0_raw.jsonl").readText()
            assertEquals("the journal must be untouched", before, after)
        } finally { dir.deleteRecursively() }
    }

    @Test fun apiKeysStillCannotBeWrittenThroughTheGenericPath() {
        val dir = root()
        try {
            val threw = runCatching {
                FileManager.write(dir, "core/config.json",
                    JSONObject().put("models", JSONArray().put(JSONObject().put("api_key", "secret"))).toString())
            }.isFailure
            assertTrue("keys must go through SecretStore", threw)
        } finally { dir.deleteRecursively() }
    }
}

/** A failed read must never turn into a smaller memory. */
class NoSilentMemoryWipeTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-nowipe").toFile().also {
        FileManager.init(it)
    }

    @Test fun anUnreadableL1IsNotReplacedByOneFact() {
        val dir = root()
        try {
            val garbage = "{ this is not json"
            FileManager.write(dir, "memory/L1_facts.json", garbage)

            val stored = ReflectionPass.store(dir, JSONObject()
                .put("text", "Командир любит чай")
                .put("source_ids", JSONArray()))
            assertFalse("the write must be refused", stored)
            assertEquals("L1 must survive verbatim",
                garbage, FileManager.read(dir, "memory/L1_facts.json"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun anUnreadableL2IsNotReplacedByOneEntry() {
        val dir = root()
        try {
            val garbage = "[not json at all"
            FileManager.write(dir, "memory/L2_diary.json", garbage)

            Diary.append(dir, "новая мысль про день", "test", null, "thought")
            assertEquals("L2 must survive verbatim",
                garbage, FileManager.read(dir, "memory/L2_diary.json"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aBlankStoreIsStillWritable() {
        val dir = root()
        try {
            // A fresh install has an empty-but-valid file and must keep working.
            FileManager.write(dir, "memory/L1_facts.json", "")
            val stored = ReflectionPass.store(dir, JSONObject()
                .put("text", "Командир любит чай")
                .put("source_ids", JSONArray()))
            assertTrue("an empty store is not corruption", stored)
        } finally { dir.deleteRecursively() }
    }
}

/** Extraction must recover after one transient failure, and provenance must stay honest. */
class ExtractionRecoveryTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-extract").toFile().also {
        FileManager.init(it)
    }

    @Test fun aSuccessfulBatchClearsTheFailureFlag() {
        val extractor = RepoSources.text("app/src/main/java/com/ruri/FactExtractor.kt")
        val success = extractor.substringAfter("private fun finishSuccessfulBatch(")
            .substringBefore("private fun recordAttempt(")
        // Without this the cooldown applied forever after a single provider error.
        assertTrue("the flag must be cleared on success",
            success.contains("\"last_extraction_failed\", false"))
    }

    @Test fun autoExtractedFactsCarryAStatus() {
        val extractor = RepoSources.text("app/src/main/java/com/ruri/FactExtractor.kt")
        assertTrue("status must be recorded", extractor.contains("Provenance.statusFor(verified)"))
        assertTrue("evidence must be verified against the journal",
            extractor.contains("Provenance.verifiedSources(root, cited)"))
    }

    @Test fun reDerivingARejectedClaimDoesNotRefreshIt() {
        val extractor = RepoSources.text("app/src/main/java/com/ruri/FactExtractor.kt")
        val merge = extractor.substringAfter("if (existing != null) {")
            .substringBefore("} else {")
        assertTrue("rejected and challenged claims must be skipped",
            merge.contains("\"rejected\"") && merge.contains("\"challenged\""))
    }
}

/** Weight must reflect status, or dead claims crowd out live memory. */
class WeightRespectsStatusTest {
    @Test fun aRejectedClaimWeighsNothing() {
        val now = System.currentTimeMillis()
        val live = MemoryWeight.weight(now, now, confirmations = 3, origin = "commander",
            status = "asserted", now = now)
        val dead = MemoryWeight.weight(now, now, confirmations = 3, origin = "commander",
            status = "rejected", now = now)
        assertEquals("rejected memory must weigh nothing", 0.0, dead, 0.000001)
        assertTrue(live > 0.0)
    }

    @Test fun aChallengedClaimWeighsLessThanAConfirmedOne() {
        val now = System.currentTimeMillis()
        val asserted = MemoryWeight.weight(now, now, 2, "commander", "asserted", now)
        val challenged = MemoryWeight.weight(now, now, 2, "commander", "challenged", now)
        assertTrue("a disputed claim must yield to a live one", challenged < asserted)
    }

    @Test fun theOrdinaryCaseIsUnchanged() {
        val now = System.currentTimeMillis()
        val withStatus = MemoryWeight.weight(now, now, 1, "commander", "asserted", now)
        val withoutStatus = MemoryWeight.weight(now, now, 1, "commander", now = now)
        // The default must match, so existing call sites keep their behaviour.
        assertEquals(withStatus, withoutStatus, 0.000001)
    }
}

/** Batch 3: the ways a reply could still go wrong. */
class ReplyPathRepairTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-reply3").toFile().also {
        FileManager.init(it)
        EmotionEngine.ensure(it)
    }

    @Test fun noLockOrderCycleBetweenEmotionAndState() {
        val emotions = RepoSources.text("app/src/main/java/com/ruri/EmotionEngine.kt")
        // LifeStateEngine holds the L4 lock and calls in here; these two also touch L4, so holding
        // the emotion monitor across that access deadlocked both threads.
        val message = emotions.substringAfter("fun onCommanderMessage(")
            .substringBefore("fun onCommanderReply(")
        val reply = emotions.substringAfter("fun onCommanderReply(")
            .substringBefore("fun onIgnored(")
        // Checked on the declaration: the body of one method runs up to the next one's doc
        // comment, and that comment happens to contain the word.
        for (name in listOf("onCommanderMessage", "onCommanderReply")) {
            assertFalse("$name must not hold the emotion monitor across L4",
                emotions.contains("@Synchronized\n    fun $name("))
        }
        assertTrue("the reason must be documented", emotions.contains("deadlock"))
    }

    @Test fun theQuestionSurvivesAToolCall() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        val fn = engine.substringAfter("internal fun leanFollowup(")
            .substringBefore("internal fun isActionJson(")
        // The chat path emits "РЕЗУЛЬТАТ ИНСТРУМЕНТА"; matching only "[ИНСТРУМЕНТ" let a tool
        // result become "the question", so the request lost the Commander's message.
        assertTrue(fn.contains("РЕЗУЛЬТАТ ИНСТРУМЕНТА"))
        assertTrue(fn.contains("ИНСТРУМЕНТ НЕ СРАБОТАЛ"))
    }

    @Test fun everyHopSeesProsePlusJson() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        val loop = engine.substringAfter("private fun runToolRequests(")
            .substringBefore("private fun currentCommanderText(")
        assertTrue("must consult stripActions on each hop",
            loop.contains("ToolLoop.stripActions(current).third"))
    }

    @Test fun theDocumentBudgetMatchesDelivery() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        // Reading more than can be delivered produced "read in full" about a truncated body, and
        // made the advertised page=N escape hatch unreachable. Anchored on the call itself,
        // because `"read_document" -> {` also appears in the action validator.
        assertTrue("must cap by the delivery limit",
            engine.contains("maxChars = TOOL_RESULT_LIMIT - 800"))
    }

    @Test fun theDiaryToolPathKeepsMarkersToo() {
        val loop = RepoSources.text("app/src/main/java/com/ruri/ToolLoop.kt")
        // The 4000-char cut without markers survived here while the chat path was fixed.
        assertFalse(loop.contains(".take(4000)"))
        assertTrue(loop.contains("trimKeepingMarker"))
    }

    @Test fun theMemoryGuardCanActuallyFail() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        val guard = engine.substringAfter("internal fun hasFullMemoryContext(")
            .substringBefore("// ---------------------------------------------------------------- diagnostics")
        // It used to test for the literal substrings L0..L3, which the architecture section
        // always provides, so it could never fail.
        assertTrue("must inspect the files", guard.contains("JSONObject(raw)"))
        assertTrue("and report corruption", guard.contains("isFailure"))
    }
}

/** Every provider must be guarded, not just the first one that had the bug. */
class ProviderGuardTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-provider").toFile().also {
        FileManager.init(it)
    }

    @Test fun closerouterWithoutAModelIsReported() {
        val dir = root()
        try {
            Route.setVariant(dir, Route.VARIANT_CLOSEROUTER)
            assertEquals("closerouter.dev", Route.unconfiguredProvider(dir))
            Route.setCloseRouterModelId(dir, "some/model")
            assertEquals(null, Route.unconfiguredProvider(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun routeraiWithoutAModelIsStillReported() {
        val dir = root()
        try {
            Route.setVariant(dir, Route.VARIANT_ROUTERAI)
            assertEquals("routerai.ru", Route.unconfiguredProvider(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aConfiguredProviderIsNotReported() {
        val dir = root()
        try {
            assertEquals(null, Route.unconfiguredProvider(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun transportRefusesToPostAnEmptyModel() {
        val transport = RepoSources.text("app/src/main/java/com/ruri/Transport.kt")
        assertTrue("the last line of defence must exist",
            transport.contains("Пустой идентификатор модели: запрос не отправлен."))
        // The kind must be "config" and the message must be the detail, in that order.
        assertTrue("arguments must be in the right order",
            transport.contains("\"config\", null,"))
    }
}

/** A corrupt document must say so instead of looking empty. */
class DocumentFailureReportTest {
    @Test fun aParseFailureIsNotAnEmptyDocument() {
        val reader = RepoSources.text("app/src/main/java/com/ruri/DocReader.kt")
        val fn = reader.substringAfter("internal fun readZipXml(")
            .substringBefore("internal fun embeddedImages(")
        // getOrDefault("") turned a parse crash into "no readable text", which reads as an empty
        // or scanned file — the Commander could not tell a broken file from a real failure.
        assertFalse("must not swallow the parse error", fn.contains(".getOrDefault(\"\")"))
        assertTrue("must surface it", fn.contains("не удалось разобрать документ"))
    }
}

/** The Routeway key must be saveable no matter which provider is selected. */
class RoutewayKeyCardTest {
    @Test fun theBridgeSavesIntoItsOwnSlot() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        assertTrue(activity.contains("fun setRoutewayKey("))
        assertTrue("must use the provider slot",
            activity.contains("Route.setProviderKey(filesDir, Route.ID_ROUTEWAY, key)"))
        assertTrue(activity.contains("fun routewayKeyState("))
    }

    @Test fun thePageNoLongerDependsOnTheActiveRouteEntry() {
        val html = RepoSources.text("app/src/main/assets/index.html")
        // Scoped by the surrounding function names; the body is short and self-contained.
        val save = html.substringAfter("function saveKey(){")
            .substringBefore("\nfunction ")
        assertFalse("must not look for a routeway entry", save.contains("findRouteModel"))
        assertTrue("must call the dedicated bridge", save.contains("setRoutewayKey"))

        val load = html.substringAfter("function loadConnection(){")
            .substringBefore("\nfunction ")
        assertFalse("presence must not come from the active route entry", load.contains("findRouteModel"))
        assertTrue("presence comes from the slot", load.contains("routewayKeyState"))
    }
}

/** The provenance view must find recent sources. */
class ProvenanceViewTest {
    @Test fun sourcesAreLookedUpFromTheTail() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        val fn = activity.substringAfter("fun readEventsByIds(")
            .substringBefore("fun challengeClaim(")
        // Reading forward from offset 0 returned the 200 oldest events, so sources pointing at
        // recent events were never found and the view always said "not found".
        assertFalse("must not scan from the start", fn.contains("afterOffset = 0L"))
        assertTrue("must read the tail", fn.contains("readLastLines"))
        assertTrue("newest first", fn.contains("asReversed()"))
    }
}

/** Copying must never take a neighbour's text. */
class CopyButtonTest {
    @Test fun aStaleIndexIsResolvedById() {
        val html = RepoSources.text("app/src/main/assets/index.html")
        val fn = html.substringAfter("function copyMessage(")
            .substringBefore("function flashCopied(")
        // Deleting a message splices the array without a re-render, so data-index goes stale.
        assertTrue("the button must carry the id", html.contains("data-action=\"copy\" data-index=\"' + i + '\" data-mid="))
        assertTrue("and the copy must re-resolve it", fn.contains("data-mid"))
    }
}

/** Batch 4: the long tail of the audit. */
class MemoryMaintenanceRepairTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-b4").toFile().also {
        FileManager.init(it)
    }

    @Test fun theAutobiographyUsesTheStrongestFacts() {
        val consolidator = RepoSources.text("app/src/main/java/com/ruri/MemoryConsolidator.kt")
        // Taking the first 80 by array position meant only the OLDEST facts could ever reach L3.
        assertTrue("must rank by weight", consolidator.contains("sortedByDescending { MemoryWeight.weightOf(it) }"))
        assertFalse("must not slice by position", consolidator.contains("until minOf(facts.length(), 80)"))
    }

    @Test fun theFactCountComparedIsTheRealOne() {
        val consolidator = RepoSources.text("app/src/main/java/com/ruri/MemoryConsolidator.kt")
        // Storing the capped number made it differ from facts.length() forever, so a rebuild
        // looked due every day and spent a model call for nothing.
        assertTrue("must store the total", consolidator.contains(".put(\"source_fact_count\", facts.length())"))
        assertTrue("and the used count separately", consolidator.contains("used_fact_count"))
    }

    @Test fun theJournalCursorNeverRunsPastTheEnd() {
        val reader = RepoSources.text("app/src/main/java/com/ruri/RawLog.kt")
        // Assuming a newline pushed the cursor beyond EOF on a torn read; the next call then saw
        // cursor > length and restarted from the beginning of the journal.
        assertTrue("must measure the terminator", reader.contains("val endOfLine ="))
        assertTrue("and never advance blindly", reader.contains("val terminator = 0"))
        assertFalse("must not assume one", reader.contains("cursor += lineLength + 1L"))
    }

    @Test fun reflectionAdvancesOnlyPastWhatItRead() {
        val pass = RepoSources.text("app/src/main/java/com/ruri/ReflectionPass.kt")
        // Using the window end skipped everything the byte budget excluded.
        assertTrue("must advance to the last consumed entry", pass.contains("bounded.lastOrNull()"))
    }

    @Test fun searchIsThoroughAndRecallStaysCheap() {
        val retriever = RepoSources.text("app/src/main/java/com/ruri/MemoryRetriever.kt")
        assertTrue("the explicit search scans everything",
            retriever.contains("collect(root, query, wanted, fullScan = true)"))
        assertTrue("prompt enrichment reads a tail",
            retriever.contains("collect(root, query, SCOPE_ALL, fullScan = false)"))
        assertTrue("and pages the journal in batches", retriever.contains("readL0Batch"))
    }

    @Test fun provenanceLookupActuallyStopsEarly() {
        val reader = RepoSources.text("app/src/main/java/com/ruri/RawLog.kt")
        // existingIds is the last function in the file, so the body runs to the end.
        val fn = reader.substringAfter("fun existingIds(")
        // `return@forEach` only skipped a line, so the "early exit" re-read the whole journal.
        assertFalse("the helper must not use a per-line skip", fn.contains("return@forEach"))
        assertTrue("it must stop once every id is found", fn.contains("while (found.size < wanted.size)"))
    }

    @Test fun aMissingKeyDoesNotNameTheWrongProvider() {
        for (file in listOf("RetryPolicy.kt", "Diagnostics.kt")) {
            val src = RepoSources.text("app/src/main/java/com/ruri/" + file)
            assertFalse(file + " must not promise a Routeway key",
                src.contains("API-ключ Routeway") || src.contains("Ключ Routeway не задан"))
        }
    }

    @Test fun aParkedRetryIsNotRecordedAsAFailure() {
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        // Recording the pre-flight "not due yet" rewrote the real error with the label "queued"
        // and pushed the retry forward every time the app was reopened.
        assertTrue(engine.contains("if (failure?.failureKind == \"queued\") throw e"))
    }

    @Test fun wakeReasonsAreMatchedStructurally() {
        val scheduler = RepoSources.text("app/src/main/java/com/ruri/LifeScheduler.kt")
        // The display string is truncated to 300 chars, so matching on it could drop a wake.
        assertTrue(scheduler.contains("reasonKeys"))
        assertTrue(scheduler.contains("shouldRunRemoteCognition(appContext, reasonKeys)"))
        assertFalse(scheduler.contains("shouldRunRemoteCognition(appContext, reason)"))
    }
}

/** Batch 4: interface and lifecycle leftovers. */
class InterfaceRepairTest {
    @Test fun aChallengedClaimCanBeRejected() {
        val html = RepoSources.text("app/src/main/assets/index.html")
        // The verdict control did not exist, so "rejected" was unreachable from the interface.
        assertTrue("a reject button must exist", html.contains("data-insight=\"reject\""))
        assertTrue("with a handler", html.contains("function rejectInsight("))
        assertTrue("and a dispatch branch", html.contains("kind === 'reject'"))
        assertTrue("calling the bridge", html.contains("call('rejectClaim'"))
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        assertTrue("the bridge must exist", activity.contains("fun rejectClaim("))
    }

    @Test fun theResumeCallbackExists() {
        val html = RepoSources.text("app/src/main/assets/index.html")
        // The app calls window.refreshOnboarding on every resume; only startOnboarding was defined.
        assertTrue(html.contains("window.refreshOnboarding ="))
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        assertTrue(activity.contains("window.refreshOnboarding"))
    }

    @Test fun theCorruptedMarkerIsGone() {
        val style = RepoSources.text("app/src/main/java/com/ruri/ChatStyle.kt")
        // A CJK character sat in a Russian marker list, so that branch could never match.
        assertFalse("no CJK may remain", style.contains("что на图"))
        assertTrue(style.contains("что на фото"))
    }

    @Test fun rolePlayErrorsAppearWhereTheButtonIs() {
        val html = RepoSources.text("app/src/main/assets/index.html")
        val fn = html.substringAfter("function toggleStyle(")
            .substringBefore("function refreshQueue(")
        // #dataStatus is on the Settings tab while the button is in the chat header.
        assertFalse(fn.contains("setStatus('dataStatus'"))
        assertTrue(fn.contains("pushError("))
    }

    @Test fun theWebViewIsTornDown() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        val destroy = activity.substringAfter("override fun onDestroy()")
            .substringBefore("private fun requestNotificationPermission(")
        for (call in listOf("removeCallbacksAndMessages", "chatExecutor.shutdownNow()", "web.destroy()")) {
            assertTrue("onDestroy must call $call", destroy.contains(call))
        }
    }

    @Test fun cleartextTrafficIsOff() {
        val manifest = RepoSources.text("app/src/main/AndroidManifest.xml")
        // Every configured endpoint is https; the flag only widened the attack surface.
        assertFalse(manifest.contains("usesCleartextTraffic=\"true\""))
    }

    @Test fun theDeadCodeIsGone() {
        val extractor = RepoSources.text("app/src/main/java/com/ruri/FactExtractor.kt")
        assertFalse("unused constant", extractor.contains("MIN_COMMANDER_MESSAGES"))
        val reader = RepoSources.text("app/src/main/java/com/ruri/RawLog.kt")
        assertFalse("documented but nonexistent index", reader.contains("INDEX_PATH"))
        val engine = RepoSources.text("app/src/main/java/com/ruri/ChatEngine.kt")
        assertFalse("dead wrapper", engine.contains("internal fun runToolLoop("))
        val queue = RepoSources.text("app/src/main/java/com/ruri/CommanderReplyQueue.kt")
        assertFalse("unused jitter helper", queue.contains("fun delayWithJitter("))
        val provenance = RepoSources.text("app/src/main/java/com/ruri/Provenance.kt")
        assertFalse("unreachable helper", provenance.contains("fun pending("))
        assertFalse("unreachable helper", provenance.contains("fun restore("))
    }

    @Test fun theJournalIndexIsActuallyShown() {
        val html = RepoSources.text("app/src/main/assets/index.html")
        // The bridge existed with no caller, so the cheapest health signal was invisible.
        assertTrue(html.contains("rawJournalIndex"))
        val reader = RepoSources.text("app/src/main/java/com/ruri/UrlReader.kt")
        assertTrue("URL reads re-validate before connecting",
            reader.contains("re-check the host immediately before the connection"))
    }
}

/**
 * Regression for a hang: the cursor must advance past the terminator, and must not assume one.
 *
 * Reading the terminator sequentially (readLine has already consumed it) made the advance wrong
 * on every line, so the loop never moved and the unit-test job spun until it was cancelled.
 */
class JournalCursorTerminationTest {
    private fun root(): java.io.File = Files.createTempDirectory("ruri-cursor").toFile().also {
        FileManager.init(it)
    }

    @Test fun anUnterminatedLastLineDoesNotSpin() {
        val dir = root()
        try {
            // The shape a journal has while an append is in flight: the last line has no newline,
            // and RandomAccessFile.readLine returns it anyway.
            val file = java.io.File(dir, "memory/L0_raw.jsonl")
            file.parentFile?.mkdirs()
            file.writeText(
                "{\"id\":\"a\",\"type\":\"message\",\"who\":\"commander\",\"text\":\"один\"}\n" +
                    "{\"id\":\"b\",\"type\":\"message\",\"who\":\"commander\",\"text\":\"два\"}"
            )

            val (entries, cursor) = RawLog.read(dir, afterOffset = 0L, limit = 10)
            assertEquals("both lines must be returned", 2, entries.size)
            assertTrue("the cursor may not pass the end", cursor <= file.length())

            // And reading again from there must terminate immediately.
            val (again, cursor2) = RawLog.read(dir, afterOffset = cursor, limit = 10)
            assertTrue("nothing left to read", again.isEmpty())
            assertEquals(cursor, cursor2)
        } finally { dir.deleteRecursively() }
    }

    @Test fun carriageReturnLinesAreConsumedInOneStep() {
        val dir = root()
        try {
            val file = java.io.File(dir, "memory/L0_raw.jsonl")
            file.parentFile?.mkdirs()
            // CRLF endings, written whole: every line must be read exactly once.
            file.writeText(
                "{\"id\":\"a\",\"type\":\"message\",\"who\":\"commander\",\"text\":\"один\"}\r\n" +
                    "{\"id\":\"b\",\"type\":\"message\",\"who\":\"commander\",\"text\":\"два\"}\r\n"
            )
            val (entries, _) = RawLog.read(dir, afterOffset = 0L, limit = 10)
            assertEquals(2, entries.size)
            assertEquals(listOf("a", "b"), entries.map { it.id })
        } finally { dir.deleteRecursively() }
    }

    @Test fun pagingStaysConsistentAcrossManyLines() {
        val dir = root()
        try {
            repeat(20) { i ->
                FileManager.appendEvent(dir, JSONObject()
                    .put("type", "message").put("who", "commander").put("text", "строка $i"))
            }
            val seen = ArrayList<String>()
            var cursor = 0L
            var guard = 0
            while (guard < 50) {
                val (page, next) = RawLog.read(dir, afterOffset = cursor, limit = 3)
                if (page.isEmpty()) break
                seen.addAll(page.map { it.id })
                assertTrue("the cursor must advance", next > cursor)
                cursor = next
                guard++
            }
            assertEquals("every line exactly once", 20, seen.size)
            assertEquals("no duplicates", 20, seen.distinct().size)
        } finally { dir.deleteRecursively() }
    }
}
}
