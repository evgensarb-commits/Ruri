package com.ruri

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FreeModelRouterTest {
    @Test
    fun excludesPaidAndDisabledProvidersEvenWhenTheyAreConfiguredFirst() {
        val root = Files.createTempDirectory("ruri-free-routes").toFile()
        try {
            FileManager.init(root)
            val models = JSONArray()
                .put(JSONObject().put("id", "gemini").put("priority", 1).put("api_key", "configured"))
                .put(JSONObject().put("id", "groq").put("priority", 2).put("free_tier", false).put("enabled", true).put("api_key", "configured"))
                .put(JSONObject().put("id", "routeway").put("priority", 3).put("free_tier", true).put("enabled", true).put("api_key", "configured"))
                .put(JSONObject().put("id", "disabled-free").put("priority", 0).put("free_tier", true).put("enabled", false).put("api_key", "configured"))

            assertEquals(listOf(2), FreeModelRouter.orderedIndices(root, models, 0, Task.CHAT))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun putsConfiguredPrimaryBeforeOtherFreeRoutesAndThenUsesPriority() {
        val root = Files.createTempDirectory("ruri-free-priority").toFile()
        try {
            FileManager.init(root)
            val models = JSONArray()
                .put(JSONObject().put("id", "routeway").put("priority", 1).put("free_tier", true).put("enabled", true).put("api_key", "configured"))
                .put(JSONObject().put("id", "requesty").put("priority", 3).put("free_tier", true).put("enabled", true).put("api_key", "configured"))
                .put(JSONObject().put("id", "other").put("priority", 2).put("free_tier", true).put("enabled", true).put("api_key", "configured"))

            assertEquals(listOf(2, 0, 1), FreeModelRouter.orderedIndices(root, models, 2, Task.CHAT))
        } finally {
            root.deleteRecursively()
        }
    }
}
