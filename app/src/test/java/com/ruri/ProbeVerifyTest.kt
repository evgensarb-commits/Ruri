package com.ruri

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live diagnostics must report per-route facts, not replay stale counters. */
class ProbeVerifyTest {
    private fun models(vararg items: JSONObject): JSONArray {
        val array = JSONArray()
        items.forEach { array.put(it) }
        return array
    }

    @Test
    fun verifyReportsNoKeyWithoutAnyNetworkCall() {
        val root = Files.createTempDirectory("ruri-probe-verify").toFile()
        try {
            val list = models(JSONObject()
                .put("id", "routerapi-cydonia")
                .put("name", "Cydonia 24B V4.1")
                .put("base_url", "https://routerai.example.org/api/v1")
                .put("api_key", "")
                .put("enabled", true))

            val report = Probe.verify(root, list, now = 1000L)
            val entry = report.optJSONObject("routerapi-cydonia")
            assertEquals("no_key", entry?.optString("result"))
            assertTrue(entry!!.isNull("http_status"))

            val meta = RouteMeta.route(root, "routerapi-cydonia")
            assertEquals("no_key", meta.optString("last_verify_result"))
            assertEquals(1000L, meta.optLong("last_verify_at"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verifySkipsDisabledRoutesAndPlainHttpBases() {
        val root = Files.createTempDirectory("ruri-probe-verify-off").toFile()
        try {
            val list = models(
                JSONObject().put("id", "off").put("enabled", false)
                    .put("base_url", "https://ok.example.org/v1").put("api_key", "k"),
                JSONObject().put("id", "plain").put("enabled", true)
                    .put("base_url", "http://insecure.example.org/v1").put("api_key", "k"))

            val report = Probe.verify(root, list, now = 2000L)
            assertEquals("disabled", report.optJSONObject("off")?.optString("result"))
            assertEquals("no_base_url", report.optJSONObject("plain")?.optString("result"))
        } finally {
            root.deleteRecursively()
        }
    }
}
