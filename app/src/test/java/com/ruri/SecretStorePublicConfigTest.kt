package com.ruri

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretStorePublicConfigTest {
    @Test fun webConfigNeverContainsDecryptedProviderKeys() {
        val raw = JSONObject()
            .put("models", org.json.JSONArray().put(JSONObject()
                .put("id", "routeway").put("name", "Routeway")
                .put("api_key", "sensitive-test-key")))
            .put("current", 0).toString()
        val safe = SecretStore.redactRuntimeConfigForWeb(raw)
        val config = JSONObject(safe)
        val model = config.getJSONArray("models").getJSONObject(0)
        assertTrue(model.optBoolean("has_key"))
        assertEquals("", model.optString("api_key"))
        assertFalse(safe.contains("sensitive-test-key"))
    }
    @Test fun webSearchConfigNeverContainsSerperCredential() {
        val raw = JSONObject()
            .put("serper_key", "serper-secret")
            .put("searxng_url", "https://search.example")
            .toString()
        val safe = SecretStore.searchConfigForWeb(raw, true)
        val config = JSONObject(safe)
        assertTrue(config.optBoolean("has_serper_key"))
        assertEquals("", config.optString("serper_key"))
        assertFalse(safe.contains("serper-secret"))
        assertEquals("https://search.example", config.optString("searxng_url"))
    }
}
