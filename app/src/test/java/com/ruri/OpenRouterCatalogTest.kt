package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterCatalogTest {
    @Test
    fun onlyExplicitFreeVariantsWithBothZeroPricesAreAccepted() {
        val raw = JSONObject().put("data", JSONArray()
            .put(JSONObject().put("id", "community/Noromaid-20B:free")
                .put("name", "Noromaid")
                .put("pricing", JSONObject().put("prompt", "0").put("completion", "0"))
                .put("context_length", 32768))
            .put(JSONObject().put("id", "community/Uncensored-13B:free")
                .put("pricing", JSONObject().put("prompt", 0).put("completion", 0.01)))
            .put(JSONObject().put("id", "community/Abliterated-8B")
                .put("pricing", JSONObject().put("prompt", 0).put("completion", 0)))
            .put(JSONObject().put("id", "community/Generic-Chat:free")
                .put("pricing", JSONObject().put("prompt", 0).put("completion", 0)))
            .put(JSONObject().put("id", "community/Uncensored-Unknown:free")
                .put("pricing", JSONObject().put("prompt", 0))))
        val result = OpenRouterCatalog.parseDetailed(raw.toString())
        assertEquals(1, result.models.length())
        assertEquals("community/Noromaid-20B:free", result.models.getJSONObject(0).getString("model"))
        assertEquals(5, result.modelsSeen)
        assertEquals(4, result.candidates)
        assertEquals(3, result.freeVariants)
        assertEquals(2, result.zeroPriced)
        assertEquals(1, result.eligible)
    }

    @Test
    fun recognizesAbliteratedUncensoredAndRoleplayFamilies() {
        assertTrue(OpenRouterCatalog.isCandidate("provider/model-abliterated"))
        assertTrue(OpenRouterCatalog.isCandidate("provider/uncensored-llama"))
        assertTrue(OpenRouterCatalog.isCandidate("provider/Noromaid-20B"))
        assertTrue(OpenRouterCatalog.isCandidate("provider/model", "Adult Roleplay"))
        assertTrue(OpenRouterCatalog.isCandidate("provider/model", description = "NSFW roleplay model"))
    }

    @Test
    fun emptyAndMalformedCatalogsDoNotCreateRoutes() {
        assertEquals(0, OpenRouterCatalog.parse("""{"data":[]}""").length())
        assertEquals(0, OpenRouterCatalog.parse("""{}""").length())
    }
}
