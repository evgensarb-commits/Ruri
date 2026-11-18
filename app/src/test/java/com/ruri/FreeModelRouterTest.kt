package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FreeModelRouterTest {
    @Test
    fun excludesPaidAndDisabledProvidersEvenWhenTheyAreConfiguredFirst() {
        val models = JSONArray()
            .put(JSONObject().put("id", "gemini").put("priority", 1))
            .put(JSONObject().put("id", "groq").put("priority", 2).put("free_tier", false).put("enabled", true))
            .put(JSONObject().put("id", "routeway").put("priority", 3).put("free_tier", true).put("enabled", true))
            .put(JSONObject().put("id", "disabled-free").put("priority", 0).put("free_tier", true).put("enabled", false))

        assertEquals(listOf(2), FreeModelRouter.orderedIndices(models, primaryIndex = 0))
    }

    @Test
    fun putsConfiguredPrimaryBeforeOtherFreeRoutesAndThenUsesPriority() {
        val models = JSONArray()
            .put(JSONObject().put("id", "routeway").put("priority", 1).put("free_tier", true))
            .put(JSONObject().put("id", "requesty").put("priority", 3).put("free_tier", true))
            .put(JSONObject().put("id", "kilo").put("priority", 2).put("free_tier", true))

        assertEquals(listOf(2, 0, 1), FreeModelRouter.orderedIndices(models, primaryIndex = 2))
    }
}
