package com.ruri

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoNsfwFlagTest {
    @Test
    fun orderedIndicesDoesNotFilterRoutesByLegacyNsfwFields() {
        val root = Files.createTempDirectory("ruri-no-nsfw-filter").toFile()
        try {
            val models = JSONArray()
                .put(JSONObject().put("id", "routeway").put("free_tier", true).put("enabled", true)
                    .put("api_key", "configured").put("nsfw_capable", false)
                    .put("nsfw_tested_at", 123L).put("nsfw_probe_status", "refused_or_disclaimer"))
                .put(JSONObject().put("id", "requesty").put("free_tier", true).put("enabled", true)
                    .put("api_key", "configured").put("nsfw_capable", true)
                    .put("nsfw_tested_at", 0L).put("nsfw_probe_status", "not_tested"))

            assertEquals(
                listOf(0, 1),
                RouteSelector.orderedIndices(root, models, 0, Task.CREATIVE, online = true, vpn = false)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun routeLoadMigratesCapsNsfwAndPreservesVisionCapability() {
        val root = Files.createTempDirectory("ruri-nsfw-meta-migration").toFile()
        try {
            FileManager.init(root)
            val legacyRoute = JSONObject()
                .put("nsfw_capable", true)
                .put("nsfw_tested_at", 123L)
                .put("nsfw_probe_status", "passed")
                .put("caps", JSONObject().put("nsfw", true).put("vision", true))
            FileManager.write(root, RouteMeta.PATH, JSONObject().put("routeway", legacyRoute).toString())

            val migrated = RouteMeta.route(root, "routeway")
            val caps = migrated.getJSONObject("caps")
            assertFalse(caps.has("nsfw"))
            assertTrue(caps.getBoolean("vision"))
            assertFalse(migrated.has("nsfw_capable"))
            assertFalse(migrated.has("nsfw_tested_at"))
            assertFalse(migrated.has("nsfw_probe_status"))

            val persisted = JSONObject(FileManager.read(root, RouteMeta.PATH))
                .getJSONObject("routeway")
            assertFalse(persisted.getJSONObject("caps").has("nsfw"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun runtimeConfigMigrationRemovesAllLegacyNsfwModelFields() {
        val root = Files.createTempDirectory("ruri-nsfw-config-migration").toFile()
        try {
            FileManager.init(root)
            val config = JSONObject().put("current", 0).put("models", JSONArray().put(
                JSONObject().put("id", "routeway").put("free_tier", true)
                    .put("nsfw_capable", false).put("nsfw_tested_at", 123L)
                    .put("nsfw_probe_status", "refused_or_disclaimer")
            ))
            SecretStore.saveRuntimeConfig(root, config.toString())

            RouteMeta.migrateLegacyNsfwFlags(root)

            val model = JSONObject(SecretStore.readRuntimeConfig(root))
                .getJSONArray("models").getJSONObject(0)
            assertFalse(model.has("nsfw_capable"))
            assertFalse(model.has("nsfw_tested_at"))
            assertFalse(model.has("nsfw_probe_status"))
        } finally {
            root.deleteRecursively()
        }
    }
}
