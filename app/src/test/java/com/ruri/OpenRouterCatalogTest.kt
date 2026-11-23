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
                .put("pricing", JSONObject().put("prompt", 0)))
            .put(JSONObject().put("id", "cognitivecomputations/dolphin-mistral-24b-venice-edition:free")
                .put("name", "Dolphin Mistral 24B Venice Edition")
                .put("description", "Uncensored conversational model")
                .put("pricing", JSONObject().put("prompt", "0").put("completion", "0"))))
        val result = OpenRouterCatalog.parseDetailed(raw.toString())
        assertEquals(2, result.models.length())
        assertTrue((0 until result.models.length()).any { result.models.getJSONObject(it).getString("model") == "community/Noromaid-20B:free" })
        assertTrue((0 until result.models.length()).any { result.models.getJSONObject(it).getString("model") == "cognitivecomputations/dolphin-mistral-24b-venice-edition:free" })
        assertEquals(6, result.modelsSeen)
        assertEquals(5, result.candidates)
        assertEquals(4, result.freeVariants)
        assertEquals(3, result.zeroPriced)
        assertEquals(2, result.eligible)
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
