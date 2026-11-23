package com.ruri

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HFModelCatalogTest {
    @Test
    fun catalogKeepsOnlyLiveRoleplayModelsWithKnownZeroInputAndOutputPrice() {
        val raw = JSONObject().put("data", org.json.JSONArray()
            .put(JSONObject().put("id", "TheDrummer/Cydonia-24B-v4.1")
                .put("providers", org.json.JSONArray()
                    .put(JSONObject().put("provider", "provider-a").put("status", "live")
                        .put("pricing", JSONObject().put("input", "0").put("output", "0"))
                        .put("context_length", 32768).put("throughput", 80).put("latency", 120))
                    .put(JSONObject().put("provider", "provider-paid").put("status", "live")
                        .put("pricing", JSONObject().put("input", "0.1").put("output", "0.2")))))
            .put(JSONObject().put("id", "Some/Rocinante-12B")
                .put("providers", org.json.JSONArray()
                    .put(JSONObject().put("provider", "provider-down").put("status", "error")
                        .put("pricing", JSONObject().put("input", "0").put("output", "0")))))
            .put(JSONObject().put("id", "Dolphin-Model")
                .put("providers", org.json.JSONArray()
                    .put(JSONObject().put("provider", "provider-a").put("status", "live")
                        .put("pricing", JSONObject().put("input", "0").put("output", "0")))))
            .put(JSONObject().put("id", "Generic-Chat")
                .put("providers", org.json.JSONArray()
                    .put(JSONObject().put("provider", "provider-a").put("status", "live")
                        .put("pricing", JSONObject().put("input", "0"))))))
        val result = HFModelCatalog.parse(raw.toString())
        assertEquals(1, result.length())
        val candidate = result.getJSONObject(0)
        assertEquals("TheDrummer/Cydonia-24B-v4.1", candidate.getString("model"))
        assertEquals("provider-a", candidate.getString("provider"))
        assertEquals(0.0, candidate.getDouble("price_input"), 0.0)
        assertEquals(0.0, candidate.getDouble("price_output"), 0.0)
        assertTrue(candidate.getBoolean("free"))
    }

    @Test
    fun explicitlyFreePromotionIsAcceptedEvenWhenRegularPriceIsNonzeroOrMissing() {
        val raw = JSONObject().put("data", org.json.JSONArray()
            .put(JSONObject().put("id", "A/Magnum-12B").put("providers", org.json.JSONArray()
                .put(JSONObject().put("provider", "promo-priced").put("status", "live")
                    .put("is_free", true)
                    .put("pricing", JSONObject().put("input", 1.2).put("output", 2.4)))))
            .put(JSONObject().put("id", "B/Cydonia-12B").put("providers", org.json.JSONArray()
                .put(JSONObject().put("provider", "promo-no-price").put("status", "live")
                    .put("is_free", true))))
            .put(JSONObject().put("id", "C/Rocinante-12B").put("providers", org.json.JSONArray()
                .put(JSONObject().put("provider", "paid").put("status", "live")
                    .put("is_free", false)
                    .put("pricing", JSONObject().put("input", 0.2).put("output", 0.4))))))
        val result = HFModelCatalog.parse(raw.toString())
        assertEquals(2, result.length())
        assertTrue((0 until result.length()).all { result.getJSONObject(it).getBoolean("free") })
        assertTrue((0 until result.length()).all { result.getJSONObject(it).getBoolean("is_free_promo") })
    }

    @Test
    fun candidatesAreRankedAndUnknownPricesAreNeverAssumedFree() {
        val raw = JSONObject().put("data", org.json.JSONArray()
            .put(JSONObject().put("id", "A/UnslopNemo-12B").put("providers", org.json.JSONArray()
                .put(JSONObject().put("provider", "p").put("status", "live")
                    .put("pricing", JSONObject().put("input", 0).put("output", 0))
                    .put("context_length", 8192))))
            .put(JSONObject().put("id", "B/Rocinante-12B").put("providers", org.json.JSONArray()
                .put(JSONObject().put("provider", "p").put("status", "live")
                    .put("pricing", JSONObject().put("input", 0).put("output", 0))
                    .put("context_length", 65536))))
            .put(JSONObject().put("id", "C/Cydonia-12B").put("providers", org.json.JSONArray()
                .put(JSONObject().put("provider", "p").put("status", "live")
                    .put("pricing", JSONObject().put("input", 0))))))
        val result = HFModelCatalog.parse(raw.toString())
        assertEquals(2, result.length())
        assertEquals("B/Rocinante-12B", result.getJSONObject(0).getString("model"))
    }
    @Test
    fun nsfwFallbackAcceptsNsfwNamedModelsOnlyWhenProviderIsExplicitlyFree() {
        val raw = JSONObject().put("data", org.json.JSONArray()
            .put(JSONObject().put("id", "community/Uncensored-Chat-8B")
                .put("providers", org.json.JSONArray()
                    .put(JSONObject().put("provider", "free-provider").put("status", "live")
                        .put("is_free", true))))
            .put(JSONObject().put("id", "community/Adult-Roleplay-7B")
                .put("providers", org.json.JSONArray()
                    .put(JSONObject().put("provider", "paid-provider").put("status", "live")
                        .put("pricing", JSONObject().put("input", 0.1).put("output", 0.2)))))
            .put(JSONObject().put("id", "community/General-Chat-7B")
                .put("providers", org.json.JSONArray()
                    .put(JSONObject().put("provider", "unknown-price").put("status", "live")))))
        val result = HFModelCatalog.parseDetailed(raw.toString(), "nsfw")
        assertEquals(1, result.models.length())
        assertEquals("community/Uncensored-Chat-8B", result.models.getJSONObject(0).getString("model"))
        assertEquals(1, result.freeProviders)
    }

    @Test
    fun nsfwFallbackRecognizesHubNsfwTagsWhenModelNameIsGeneric() {
        val model = JSONObject().put("id", "community/Generic-Chat-8B")
            .put("tags", org.json.JSONArray().put("text-generation").put("nsfw"))
            .put("providers", org.json.JSONArray()
                .put(JSONObject().put("provider", "free-provider").put("status", "live")
                    .put("is_free", true)))
        val result = HFModelCatalog.parseDetailed(
            JSONObject().put("data", org.json.JSONArray().put(model)).toString(), "nsfw"
        )
        assertEquals(1, result.models.length())
        assertEquals("community/Generic-Chat-8B", result.models.getJSONObject(0).getString("model"))
    }

}
