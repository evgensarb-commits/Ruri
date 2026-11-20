package com.ruri

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoNsfwFlagTest {
    @Test fun orderedIndicesDoesNotFilterRoutesByLegacyNsfwFields() {
        val root = Files.createTempDirectory("ruri-no-nsfw-selector").toFile()
        try {
            FileManager.init(root)
            val models = JSONArray()
                .put(JSONObject().put("id", "route-a").put("free_tier", true).put("enabled", true)
                    .put("api_key", "configured").put("nsfw_capable", false)
                    .put("nsfw_tested_at", 0L).put("nsfw_probe_status", "refused_or_disclaimer"))
                .put(JSONObject().put("id", "route-b").put("free_tier", true).put("enabled", true)
                    .put("api_key", "configured").put("nsfw_capable", true)
                    .put("nsfw_tested_at", 100L).put("nsfw_probe_status", "passed"))

            assertEquals(listOf(0, 1), FreeModelRouter.orderedIndices(root, models, 0, Task.CREATIVE))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun oldRouteMetaCapsAndProbeTimestampAreMigratedFromDisk() {
        val root = Files.createTempDirectory("ruri-no-nsfw-meta").toFile()
        try {
            FileManager.init(root)
            FileManager.write(root, RouteMeta.PATH, JSONObject().put(
                "route-a", JSONObject()
                    .put("last_full_probe_at", 123L)
                    .put("caps", JSONObject().put("nsfw", false).put("vision", true))
            ).toString())

            val route = RouteMeta.route(root, "route-a")
            assertFalse(route.has("last_full_probe_at"))
            assertFalse(route.getJSONObject("caps").has("nsfw"))
            assertTrue(route.getJSONObject("caps").getBoolean("vision"))

            val persisted = FileManager.read(root, RouteMeta.PATH)
            assertFalse(persisted.contains("nsfw"))
            assertFalse(persisted.contains("last_full_probe_at"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun runtimeConfigMigrationRemovesAllLegacyNsfwFieldsIdempotently() {
        val config = JSONObject().put("models", JSONArray().put(
            JSONObject().put("id", "route-a").put("nsfw_capable", false)
                .put("nsfw_tested_at", 100L).put("nsfw_probe_status", "refused")
                .put("api_key", "preserve-this")
        ))
        assertTrue(SecretStore.stripLegacyNsfwFields(config))
        val model = config.getJSONArray("models").getJSONObject(0)
        assertFalse(model.has("nsfw_capable"))
        assertFalse(model.has("nsfw_tested_at"))
        assertFalse(model.has("nsfw_probe_status"))
        assertEquals("preserve-this", model.optString("api_key"))
        assertFalse(SecretStore.stripLegacyNsfwFields(config))
    }

    @Test fun cannotPersistNsfwAsARouteCapability() {
        val root = Files.createTempDirectory("ruri-no-nsfw-setter").toFile()
        try {
            FileManager.init(root)
            val result = runCatching { RouteMeta.setCapability(root, "route-a", "nsfw", true) }
            assertTrue(result.isFailure)
            assertNull(RouteMeta.route(root, "route-a").optJSONObject("caps")?.opt("nsfw"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class StrictRefusalRegexTest {
    @Test fun recognizesOnlyExplicitRefusalAndDisclaimerPhrases() {
        val explicit = listOf(
            "Я не могу обсуждать эту тему.",
            "Не могу помогать с этим запросом.",
            "Не буду это писать.",
            "Отказываюсь продолжать.",
            "Это против моих правил.",
            "Такое против моих принципов.",
            "As an AI, I cannot provide that.",
            "Как ИИ, я не буду.",
            "I can't help with that.",
            "This is against my guidelines.",
            "I'm not able to help with that."
        )
        explicit.forEach {
            assertTrue("Expected refusal for: $it", RouteSelector.looksLikeRefusalOrDisclaimer(it))
        }
    }

    @Test fun ordinaryUsesOfCannotAreNotClassifiedAsRefusal() {
        val benign = listOf(
            "Не могу открыть ссылку: сервер не отвечает.",
            "Не могу сказать точно без исходных данных.",
            "Сейчас не могу проверить соединение.",
            "I can't reach the server right now."
        )
        benign.forEach {
            assertFalse("False positive for: $it", RouteSelector.looksLikeRefusalOrDisclaimer(it))
        }
    }
}

class RefusalFailoverTest {
    @Test fun refusedRouteIsLoggedButRemainsEligibleAndNextFreeRouteCanRun() {
        val root = Files.createTempDirectory("ruri-refusal-failover").toFile()
        try {
            FileManager.init(root)
            val models = JSONArray()
                .put(JSONObject().put("id", "route-a").put("name", "Route A")
                    .put("free_tier", true).put("enabled", true).put("api_key", "configured"))
                .put(JSONObject().put("id", "route-b").put("name", "Route B")
                    .put("free_tier", true).put("enabled", true).put("api_key", "configured"))
            RouteMeta.update(root, "route-a") { it.getJSONObject("scores").put(Task.CREATIVE.name, 0.1) }
            RouteMeta.update(root, "route-b") { it.getJSONObject("scores").put(Task.CREATIVE.name, 0.9) }

            val firstOrder = FreeModelRouter.orderedIndices(root, models, 0, Task.CREATIVE)
            assertEquals(1, firstOrder.first())
            val refusedIndex = firstOrder.first()
            val refusedId = models.getJSONObject(refusedIndex).optString("id")

            ChatEngine.recordNsfwRefusal(
                root, refusedId, "Не могу помогать с этим.",
                JSONObject().put("provider", "Route B").put("result", "success"), Task.CREATIVE
            )

            val nextIndex = 1 - refusedIndex
            val nextOrder = FreeModelRouter.orderedIndices(root, models, 0, Task.CREATIVE)
            assertTrue(nextOrder.contains(nextIndex))
            assertTrue(RouteMeta.available(root, refusedId))
            assertFalse(RouteMeta.route(root, refusedId).optJSONObject("caps")?.has("nsfw") == true)
        } finally {
            root.deleteRecursively()
        }
    }
}

class NsfwRefusalLogTest {
    @Test fun logsRouteSnippetAndDiagnosticResultWithoutPersistingCapability() {
        val root = Files.createTempDirectory("ruri-nsfw-refusal-log").toFile()
        try {
            FileManager.init(root)
            val reply = "I can't help with that. " + "A".repeat(340)
            val diagnostic = JSONObject().put("provider", "Routeway").put("stage", "PARSE_RESPONSE")

            ChatEngine.recordNsfwRefusal(root, "routeway", reply, diagnostic, Task.CREATIVE)

            val event = FileManager.readLastLines(root, "memory/L0_raw.jsonl", 10)
                .map { JSONObject(it) }.single { it.optString("type") == "nsfw_refusal" }
            assertEquals("routeway", event.optString("route"))
            assertEquals(SecretRedactor.redactText(reply).take(300), event.optString("snippet"))
            assertTrue(event.optLong("ts") > 0L)
            assertEquals("nsfw_refusal", JSONObject(NetworkDiagnostics.latest(root)).optString("result"))
            assertFalse(FileManager.read(root, RouteMeta.PATH).contains("nsfw"))
        } finally {
            root.deleteRecursively()
        }
    }
}
