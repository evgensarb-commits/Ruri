package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretStorePublicConfigTest {
    @Test fun webConfigNeverContainsDecryptedProviderKeys() {
        val raw = JSONObject()
            .put("models", JSONArray().put(JSONObject()
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

    @Test fun localBackupSerializersNeverExportLegacyPlaintextKeys() {
        val rawConfig = JSONObject()
            .put("models", JSONArray().put(JSONObject()
                .put("id", "routeway").put("api_key", "model-secret").put("has_key", true)))
            .put("api_key", "root-secret").toString()
        val safeConfig = SecretStore.configForBackup(rawConfig)
        assertFalse(safeConfig.contains("model-secret"))
        assertFalse(safeConfig.contains("root-secret"))
        val model = JSONObject(safeConfig).getJSONArray("models").getJSONObject(0)
        assertEquals("", model.optString("api_key"))
        assertFalse(model.has("has_key"))
    }
}
