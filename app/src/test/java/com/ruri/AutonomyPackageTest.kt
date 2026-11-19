package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomyPackageTest {
    @Test
    fun thinkerSkipsMinimalContextRoutes() {
        assertTrue(Thinker.shouldSkipMinimalContextRoute(JSONObject().put("minimal_context", true)))
        assertFalse(Thinker.shouldSkipMinimalContextRoute(JSONObject().put("minimal_context", false)))
        assertFalse(Thinker.shouldSkipMinimalContextRoute(JSONObject()))
    }

    @Test
    fun googleCseJsonIsConvertedToBoundedResults() {
        val raw = JSONObject().put("items", JSONArray()
            .put(JSONObject().put("title", "Example").put("link", "https://example.org/")
                .put("snippet", "A useful result")))
            .toString()
        val result = JSONObject(WebSearch.parseCseResponse(raw, "example"))
        assertEquals("example", result.getString("query"))
        assertEquals(1, result.getJSONArray("results").length())
        assertEquals("https://example.org/", result.getJSONArray("results").getJSONObject(0).getString("url"))
    }

    @Test
    fun googleCaptchaReturnsHonestUnavailableResult() {
        val result = JSONObject(WebSearch.parseGoogleHtml("<html><title>Captcha</title>unusual traffic</html>", "test"))
        assertEquals("Поиск недоступен", result.getString("error"))
    }

    @Test
    fun googleHtmlScrapeExtractsOrganicResult() {
        val html = """<a href="/url?q=https%3A%2F%2Fexample.org%2F&sa=U"><h3>Example page</h3></a>"""
        val result = JSONObject(WebSearch.parseGoogleHtml(html, "example"))
        assertEquals(1, result.getJSONArray("results").length())
        assertEquals("Example page", result.getJSONArray("results").getJSONObject(0).getString("title"))
    }
}