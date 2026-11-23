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
    @Test fun localBackupSerializersNeverExportLegacyPlaintextKeys() {
        val rawConfig = JSONObject()
            .put("models", org.json.JSONArray().put(JSONObject()
                .put("id", "routeway").put("api_key", "model-secret").put("has_key", true)))
            .put("api_key", "root-secret").toString()
        val safeConfig = SecretStore.configForBackup(rawConfig)
        assertFalse(safeConfig.contains("model-secret"))
        assertFalse(safeConfig.contains("root-secret"))
        val model = JSONObject(safeConfig).getJSONArray("models").getJSONObject(0)
        assertEquals("", model.optString("api_key"))
        assertFalse(model.has("has_key"))

        val safeSearch = SecretStore.searchConfigForBackup(
            JSONObject().put("serper_key", "search-secret").toString()
        )
        assertFalse(safeSearch.contains("search-secret"))
        assertEquals("", JSONObject(safeSearch).optString("serper_key"))
    }
}
