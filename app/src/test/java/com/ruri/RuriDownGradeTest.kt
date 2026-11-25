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
        assertEquals(Route.BASE_URL, withKey.optString("base_url"))
        assertEquals(Route.MODEL, withKey.optString("model"))
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
            assertEquals(Route.ID, model.optString("id"))
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
            val snapshot = JSONObject(Diagnostics.snapshot(dir, online = true, vpn = true))
            val route = snapshot.getJSONObject("route")
            assertEquals(Route.ID, route.optString("id"))
            assertEquals(Route.MODEL, route.optString("model"))
            assertEquals(Route.BASE_URL, route.optString("base_url"))
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

    @Test fun thoughtGateStaysClosedBelowItsThreshold() {
        val dir = root()
        try {
            EmotionEngine.applyDelta(dir, JSONObject()
                .put("boredom", -100.0).put("longing", -100.0), "test")
            // No pressure at all: she stays silent and spends nothing.
            assertFalse(EmotionEngine.thoughtGate(dir, System.currentTimeMillis(), 0L))
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
            repeat(45) { RouteMeta.recordAttempt(dir, Route.ID) }
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
}
