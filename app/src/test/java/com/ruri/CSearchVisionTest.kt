package com.ruri

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRouterChainTest {
    @Test fun triesProvidersInOrderAndSkipsErrorsAndEmptyResults() {
        val calls = mutableListOf<String>()
        val expected = JSONObject().put("query", "q").put("provider", "wikipedia")
            .put("results", JSONArray().put(JSONObject().put("title", "Wiki").put("url", "https://ru.wikipedia.org")))
            .toString()
        val actual = SearchRouter.firstSuccessful(listOf(
            { calls += "brave"; null },
            { calls += "yandex"; """{"error":"unavailable"}""" },
            { calls += "wikipedia"; """{"query":"q","results":[]}""" },
            { calls += "google"; expected }
        ))
        assertEquals(expected, actual)
        assertEquals(listOf("brave", "yandex", "wikipedia", "google"), calls)
    }

    @Test fun providerExceptionsDoNotStopFallback() {
        val actual = SearchRouter.firstSuccessful(listOf(
            { throw IllegalStateException("offline") },
            { """{"query":"q","results":[{"title":"ok","url":"https://example.org"}]}""" }
        ))
        assertTrue(JSONObject(actual!!).getJSONArray("results").length() == 1)
    }
}

class UrlReaderExtractTest {
    @Test fun stripsScriptsStylesAndNavigationAndDecodesCommonEntities() {
        val html = "<html><head><style>bad css</style><script>secret()</script></head>" +
            "<body><nav>menu</nav><p>Tom &amp; Jerry</p><p>A &lt; B</p><footer>end</footer></body></html>"
        val text = UrlReader.extractHtml(html)
        assertTrue(text.contains("Tom & Jerry"))
        assertTrue(text.contains("A < B"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("bad css"))
        assertFalse(text.contains("menu"))
        assertFalse(text.contains("end"))
    }
}

class UrlReaderDenylistTest {
    @Test fun denylistMatchesSensitiveUrlMarkersCaseInsensitively() {
        assertTrue(UrlReader.isDeniedUrl("https://example.org/csam"))
        assertTrue(UrlReader.isDeniedUrl("https://example.org/underage-sex"))
        assertFalse(UrlReader.isDeniedUrl("https://example.org/safe-article"))
    }
}

class ReadUrlToolTest {
    @Test fun acceptsOnlyHttpUrlsWithoutUnexpectedKeys() {
        assertEquals("read_url", ChatEngine.parseAction("""{"action":"read_url","url":"https://example.org"}""")?.optString("action"))
        assertNull(ChatEngine.parseAction("""{"action":"read_url","url":"file:///etc/passwd"}"""))
        assertNull(ChatEngine.parseAction("""{"action":"read_url","url":"https://example.org","extra":true}"""))
    }

    @Test fun invalidSchemeIsRejectedBeforeAnyNetworkRequest() {
        val root = Files.createTempDirectory("ruri-read-url").toFile()
        try {
            val (ok, message) = UrlReader.read(root, "file:///etc/passwd")
            assertFalse(ok)
            assertEquals("Недопустимый URL", message)
        } finally {
            root.deleteRecursively()
        }
    }
}
