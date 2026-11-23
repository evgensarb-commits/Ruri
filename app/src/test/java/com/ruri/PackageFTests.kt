package com.ruri

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun packageFRoot(prefix: String): File =
    Files.createTempDirectory(prefix).toFile().also { FileManager.init(it) }

class BalancedBraceStripTest {
    @Test fun nestedObjectsAndBracesInsideStringsAreExtractedAsOneAction() {
        val action = """{"action":"read_shelf","name":"notes.txt","options":{"literal":"a } and { b","nested":{"ok":true}}}"""
        val (clean, diary, tools) = ToolLoop.stripActions("До действия $action после действия")
        assertEquals("До действия  после действия", clean)
        assertTrue(diary.isEmpty())
        assertEquals(1, tools.size)
        assertEquals("read_shelf", tools.single().optString("action"))
        assertEquals("a } and { b", tools.single().getJSONObject("options").optString("literal"))
    }
}

class OnlyJsonFallbackTest {
    @Test fun onlyJsonIsNeverConsideredVisibleProse() {
        assertNull(ToolLoop.proseCandidate("""{"action":"search","query":"коты"}"""))
        assertNull(ToolLoop.proseCandidate("""{"summary":"only json"}"""))
        assertEquals("Обычный ответ.", ToolLoop.proseCandidate("Обычный ответ."))
    }
}

class NoBatteryGateTest {
    @Test fun liveNeedIsIndependentOfBatteryAndOnlyCommanderSilenceChangesConnectionNeed() {
        val now = System.currentTimeMillis()
        val recent = LifeStateEngine.calculateNeeds(now - 60_000L, now)
        val silent = LifeStateEngine.calculateNeeds(now - 24L * 60L * 60L * 1000L, now)
        assertEquals(0.38, recent.getDouble("live"), 0.0)
        assertEquals(0.38, silent.getDouble("live"), 0.0)
        assertTrue(silent.getDouble("love_commander") > recent.getDouble("love_commander"))
    }
}

class NoVpnPreFilterTest {
    @Test fun eligibleFreeRouteSurvivesOfflineAndVpnMetadata() {
        val root = packageFRoot("ruri-no-vpn-prefilter")
        try {
            val models = JSONArray().put(
                JSONObject()
                    .put("id", "vpn-route")
                    .put("name", "VPN route")
                    .put("api_key", "test-key")
                    .put("free_tier", true)
                    .put("enabled", true)
                    .put("needs_vpn", true)
            )
            val order = RouteSelector.orderedIndices(
                root, models, 0, Task.CHAT, online = false, vpn = false
            )
            assertEquals(listOf(0), order)
        } finally {
            root.deleteRecursively()
        }
    }
}

class DiaryThoughtsOnlyTest {
    @Test fun defaultDiaryViewIncludesThoughtsAndLegacyEntriesButNotSaid() {
        val selected = MemoryConsolidator.thoughtEntries(
            listOf(
                JSONObject().put("kind", "thought").put("text", "мысль"),
                JSONObject().put("kind", "said").put("text", "сказанное"),
                JSONObject().put("text", "старая запись без kind")
            )
        )
        assertEquals(listOf("мысль", "старая запись без kind"), selected.map { it.optString("text") })
    }
}


class LastSeenSuccessfulOnlyTest {
    @Test fun recoveryDoesNotInferLastSeenButSuccessfulReplyMaySetIt() {
        val root = packageFRoot("ruri-last-seen-success")
        try {
            val commanderTs = 1_790_000_000_000L
            FileManager.appendEvent(root, JSONObject()
                .put("type", "message").put("who", "commander")
                .put("text", "Привет").put("timestamp", commanderTs))
            val recovered = LifeStateEngine.recoverStateFromHistory(root, "{broken")
            assertEquals(0L, recovered.optLong("last_seen_commander"))

            ChatEngine.appendFinalReply(root, "Ответила.", "test-route")
            val saved = JSONObject(FileManager.read(root, "memory/L4_state.json"))
            assertEquals(commanderTs, saved.optLong("last_seen_commander"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class VisionProbeLoggedTest {
    @Test fun manuallyDeclaredVisionRouteIsSelectedWithoutProbeOrAutoCapabilityMutation() {
        val root = packageFRoot("ruri-vision-manual-declaration")
        try {
            val models = JSONArray().put(
                JSONObject()
                    .put("id", "vision-route")
                    .put("name", "Vision route")
                    .put("base_url", "https://provider.example/v1")
                    .put("api_key", "test-key")
                    .put("free_tier", true)
                    .put("enabled", true)
                    .put("vision", true)
                    .put("priority", 1)
            )
            val selected = Vision.findVisionRoute(
                root, models, 0, online = true, vpn = false,
                probeRoute = { throw AssertionError("Manual declaration must not probe provider") }
            )
            assertEquals("vision-route", selected?.optString("id"))
            val events = FileManager.readLastLines(root, "memory/L0_raw.jsonl", 50)
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            assertFalse(events.any { it.optString("type") == "vision_probe_result" })
            val self = JSONObject(FileManager.read(root, "memory/self_model.json"))
            assertFalse(self.optJSONArray("limitations").toString().contains("vision_probe:"))
            assertFalse(Capabilities.bodySection(root).contains("vision_probe:"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class ThinkerShelfDescTest {
    @Test fun imageShelfEntryUsesDescriptionSidecarInsteadOfBinaryBytes() {
        val root = packageFRoot("ruri-thinker-shelf-desc")
        try {
            val shelf = File(root, "shelf")
            val desc = File(shelf, ".desc")
            desc.mkdirs()
            File(shelf, "photo.png").writeBytes(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00)
            )
            File(desc, "photo.png.txt").writeText("На фото красный велосипед.", Charsets.UTF_8)
            assertEquals("На фото красный велосипед.", Thinker.shelfContextText(root, "photo.png"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class L3ThoughtOnlyTest {
    @Test fun saidEntriesNeverEnterTheL3SourcePage() {
        val selected = MemoryConsolidator.thoughtEntries(
            listOf(
                JSONObject().put("sequence", 1).put("kind", "said").put("text", "ответ"),
                JSONObject().put("sequence", 2).put("kind", "thought").put("text", "мысль")
            )
        )
        assertEquals(1, selected.size)
        assertEquals("thought", selected.single().optString("kind"))
    }
}

class OutcomeDigestTest {
    @Test fun digestCapsFailureLinesAndIncludesPreviousTurnOutcome() {
        val root = packageFRoot("ruri-outcome-digest")
        try {
            FileManager.appendEvent(root, JSONObject()
                .put("type", "message").put("who", "commander").put("text", "Найди страницу"))
            FileManager.appendEvent(root, JSONObject()
                .put("type", "tool_result").put("action", "search").put("ok", false).put("text", "timeout"))
            FileManager.appendEvent(root, JSONObject()
                .put("type", "tool_result").put("action", "read_url").put("ok", true).put("text", "page text"))
            FileManager.appendEvent(root, JSONObject()
                .put("type", "vision_probe_result").put("route", "route-a").put("capable", false).put("snippet", "no vision"))
            FileManager.appendEvent(root, JSONObject().put("type", "shelf_image_unreadable").put("text", "not an image"))
            FileManager.appendEvent(root, JSONObject().put("type", "nsfw_refusal").put("snippet", "refused"))
            FileManager.appendEvent(root, JSONObject().put("type", "thinker_error").put("message", "cycle failed"))
            FileManager.appendEvent(root, JSONObject()
                .put("id", "answer-123").put("ts", 123456L).put("type", "message").put("who", "ruri").put("text", "Готово"))

            val digest = OutcomeDigest.build(root)
            val failureLines = digest.lines().count { it.startsWith("- ") }
            assertTrue(failureLines <= 5)
            assertTrue(digest.contains("answer-123"))
            assertTrue(digest.contains("123456"))
            assertTrue(digest.contains("инструменты ok=1, failed=1"))
            assertTrue(digest.contains("vision=не подтверждено маршрутом route-a"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun staleToolSuccessBeforeLatestWakeDoesNotValidateNewClaim() {
        val root = packageFRoot("ruri-outcome-stale-tool")
        try {
            FileManager.appendEvent(root, JSONObject()
                .put("type", "message").put("who", "commander").put("text", "Старый ход"))
            FileManager.appendEvent(root, JSONObject().put("type", "wake").put("reason", "old_cycle"))
            FileManager.appendEvent(root, JSONObject()
                .put("type", "tool_result").put("action", "search").put("ok", true).put("text", "old results"))
            FileManager.appendEvent(root, JSONObject().put("type", "wake").put("reason", "new_cycle"))

            val correction = OutcomeDigest.verifyClaims(root, "Я поискала в интернете.")
            assertNotNull(correction)
            assertTrue(correction!!.optString("message").contains("поиск"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun contradictorySearchAndReadClaimsBecomePendingCorrection() {
        val root = packageFRoot("ruri-outcome-correction")
        try {
            FileManager.appendEvent(root, JSONObject()
                .put("type", "message").put("who", "commander").put("text", "Проверь страницу"))
            val correction = OutcomeDigest.verifyClaims(
                root, "Я поискала в интернете и прочитала документ."
            )
            assertNotNull(correction)
            assertTrue(correction!!.optString("message").contains("поиск"))
            val state = JSONObject(FileManager.read(root, "memory/L4_state.json"))
            assertTrue(state.optJSONObject("pending_correction") != null)
            assertTrue(OutcomeDigest.build(root).contains("ОБЯЗАТЕЛЬНОЕ ИСПРАВЛЕНИЕ"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class SelfCheckTest {
    @Test fun selfCheckIsWhitelistedAndReturnsCompactOutcomeDigest() {
        val root = packageFRoot("ruri-self-check")
        try {
            FileManager.appendEvent(root, JSONObject().put("type", "model_error").put("model", "route-a"))
            val action = ChatEngine.parseAction("""{"action":"self_check"}""")
            assertNotNull(action)
            val (ok, result) = ChatEngine.executeTool(root, "self_check", action!!)
            assertTrue(ok)
            assertTrue(result.contains("model_error"))
            val reply = ChatEngine.runToolLoop(root, """{"action":"self_check"}""", JSONArray()) {
                "Проверка завершена."
            }
            assertEquals("Проверка завершена.", reply)
        } finally {
            root.deleteRecursively()
        }
    }
}

class NotifierUniqueIdTest {
    @Test fun thoughtNotificationsUseMessageSpecificIds() {
        val first = Notifier.notificationIdFor("thought-message-one")
        val second = Notifier.notificationIdFor("thought-message-two")
        assertNotEquals(first, second)
        assertNotEquals(Notifier.THOUGHT_NOTIFICATION_ID, first)
        assertNotEquals(Notifier.THOUGHT_NOTIFICATION_ID, second)
    }
}

class EmotionRedesignTest {
    @Test fun longingRisesDuringSilence() {
        val root = packageFRoot("ruri-longing-silence")
        try {
            val now = System.currentTimeMillis()
            val state = JSONObject(FileManager.read(root, "memory/emotions.json"))
                .put("updated_at", now - 3_600_000L)
                .put("last_tick_at", now - 3_600_000L)
            FileManager.write(root, "memory/emotions.json", state.toString())
            val before = EmotionEngine.readValues(root).optDouble("longing")
            EmotionEngine.tick(root, now, now - 3L * 3_600_000L, now - 3_600_000L)
            assertTrue(EmotionEngine.readValues(root).optDouble("longing") > before)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun passiveCycleDoesNotChangeFatigueOrBoredom() {
        val root = packageFRoot("ruri-passive-cycle")
        try {
            val before = EmotionEngine.readValues(root)
            EmotionEngine.onCycle(root)
            val values = EmotionEngine.readValues(root)
            assertEquals(before.getDouble("fatigue"), values.getDouble("fatigue"), 0.0)
            assertEquals(before.getDouble("boredom"), values.getDouble("boredom"), 0.0)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun oldHurtAndCalmValuesAreMigratedAndRetired() {
        val root = packageFRoot("ruri-emotion-redesign-migration")
        try {
            val config = JSONObject(FileManager.read(root, "core/emotions.json"))
            val channels = config.getJSONObject("channels")
                .put("hurt", JSONObject().put("min", 0).put("max", 100).put("base", 10).put("half_life_h", 24))
                .put("calm", JSONObject().put("min", 0).put("max", 100).put("base", 60).put("half_life_h", 6))
            config.put("schema_version", 1)
            FileManager.write(root, "core/emotions.json", config.toString(2))

            val state = JSONObject(FileManager.read(root, "memory/emotions.json"))
            state.getJSONObject("values")
                .put("hurt", 40.0)
                .put("calm", 75.0)
            FileManager.write(root, "memory/emotions.json", state.toString(2))

            EmotionEngine.ensure(root)

            val migratedConfig = JSONObject(FileManager.read(root, "core/emotions.json"))
            val migratedValues = EmotionEngine.readValues(root)
            val migratedChannels = migratedConfig.getJSONObject("channels")
            assertFalse(migratedChannels.has("hurt"))
            assertFalse(migratedChannels.has("calm"))
            assertFalse(migratedValues.has("hurt"))
            assertFalse(migratedValues.has("calm"))
            assertEquals(30.0, migratedValues.getDouble("anger"), 0.0)
            assertEquals(40.0, migratedValues.getDouble("sadness"), 0.0)
            assertTrue(migratedChannels.has("longing"))
            assertEquals(2, migratedConfig.optInt("schema_version"))
        } finally {
            root.deleteRecursively()
        }
    }
}
