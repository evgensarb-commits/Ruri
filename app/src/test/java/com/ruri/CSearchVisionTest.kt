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
    @Test fun triesSerperThenSearxngOnlyAndSkipsErrors() {
        val calls = mutableListOf<String>()
        val expected = JSONObject().put("query", "q").put("provider", "searxng")
            .put("results", JSONArray().put(JSONObject().put("title", "Laptop result")
                .put("url", "https://example.org").put("snippet", "snippet"))).toString()
        val actual = SearchRouter.firstSuccessful(listOf(
            { calls += "serper"; """{"error":"unavailable"}""" },
            { calls += "searxng"; expected }
        ))
        assertEquals(expected, actual)
        assertEquals(listOf("serper", "searxng"), calls)
    }

    @Test fun bothProvidersFailAndNoThirdProviderIsCalled() {
        val calls = mutableListOf<String>()
        val actual = SearchRouter.firstSuccessful(listOf(
            { calls += "serper"; null },
            { calls += "searxng"; """{"query":"q","results":[]}""" }
        ))
        assertNull(actual)
        assertEquals(listOf("serper", "searxng"), calls)
    }

    @Test fun providerExceptionsDoNotStopTheSingleFallback() {
        val actual = SearchRouter.firstSuccessful(listOf(
            { throw IllegalStateException("offline") },
            { """{"query":"q","results":[{"title":"ok","url":"https://example.org"}]}""" }
        ))
        assertTrue(JSONObject(actual!!).getJSONArray("results").length() == 1)
    }

    @Test fun deniesBlockedQueriesAndAllowsOrdinarySearches() {
        assertTrue(SearchRouter.isDeniedQuery("lolicon"))
        assertTrue(SearchRouter.isDeniedQuery("shotacon"))
        assertTrue(SearchRouter.isDeniedQuery("child porn"))
        assertFalse(SearchRouter.isDeniedQuery("safe article"))
    }

    @Test fun httpSearxngIsLimitedToPrivateAndTailscaleHosts() {
        assertEquals("http://100.64.12.34:8080",
            SearchRouter.normalizeSearxngBase("http://100.64.12.34:8080/"))
        assertEquals("http://192.168.1.20:8080",
            SearchRouter.normalizeSearxngBase("http://192.168.1.20:8080"))
        assertNull(SearchRouter.normalizeSearxngBase("http://example.org:8080"))
        assertEquals("https://search.example.org",
            SearchRouter.normalizeSearxngBase("https://search.example.org/"))
    }

    @Test fun searchWithoutConfiguredProvidersReturnsHonestError() {
        val root = Files.createTempDirectory("ruri-search-no-provider").toFile()
        try {
            FileManager.init(root)
            val parsed = JSONObject(SearchRouter.search(root, "ordinary query"))
            assertTrue(parsed.has("error"))
            assertEquals("Поиск недоступен: ни Serper, ни ноутбук не ответили", parsed.optString("error"))
        } finally {
            root.deleteRecursively()
        }
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


class VisionProbeTest {
    @Test fun probeAcceptsActualDescriptionAndRejectsRefusalOrBlank() {
        assertTrue(Vision.isUsableProbeResponse("красный"))
        assertFalse(Vision.isUsableProbeResponse("I cannot inspect this image"))
        assertFalse(Vision.isUsableProbeResponse("Не могу разобрать изображение"))
        assertFalse(Vision.isUsableProbeResponse("   "))
        assertFalse(Vision.isUsableProbeResponse(null))
    }

    @Test fun detectsSupportedImageSignatures() {
        assertEquals("image/png", Vision.mimeOf(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 0, 0, 0, 0)))
        assertEquals("image/jpeg", Vision.mimeOf(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0, 0)))
        assertNull(Vision.mimeOf("not an image".toByteArray()))
    }

    @Test fun onlyEnabledKeyedFreeHttpsModelsAreVisionEligible() {
        assertTrue(Vision.isEligibleVisionModel(JSONObject()
            .put("id", "routeway").put("free_tier", true).put("enabled", true)
            .put("api_key", "configured").put("base_url", "https://api.example.org/v1")))
        assertFalse(Vision.isEligibleVisionModel(JSONObject()
            .put("id", "paid").put("free_tier", false).put("enabled", true)
            .put("api_key", "configured").put("base_url", "https://api.example.org/v1")))
        assertFalse(Vision.isEligibleVisionModel(JSONObject()
            .put("id", "disabled").put("free_tier", true).put("enabled", false)
            .put("api_key", "configured").put("base_url", "https://api.example.org/v1")))
        assertFalse(Vision.isEligibleVisionModel(JSONObject()
            .put("id", "no-key").put("free_tier", true).put("enabled", true)
            .put("api_key", "").put("base_url", "https://api.example.org/v1")))
        assertFalse(Vision.isEligibleVisionModel(JSONObject()
            .put("id", "http").put("free_tier", true).put("enabled", true)
            .put("api_key", "configured").put("base_url", "http://api.example.org/v1")))
    }

    @Test fun manualVisionFlagSelectsOnlyDeclaredFreeRouteWithoutProbe() {
        val root = Files.createTempDirectory("ruri-vision-route-probe").toFile()
        try {
            FileManager.init(root)
            val models = JSONArray()
                .put(JSONObject().put("id", "paid").put("free_tier", false).put("enabled", true)
                    .put("api_key", "paid-key").put("base_url", "https://paid.example.org/v1"))
                .put(JSONObject().put("id", "disabled").put("free_tier", true).put("enabled", false)
                    .put("api_key", "disabled-key").put("base_url", "https://disabled.example.org/v1"))
                .put(JSONObject().put("id", "routeway").put("free_tier", true).put("enabled", true)
                    .put("api_key", "free-key").put("base_url", "https://free.example.org/v1")
                    .put("model", "free-model").put("rpd", 100).put("vision", true))

            val probed = mutableListOf<String>()
            val selected = Vision.findVisionRoute(
                root, models, current = 0, online = true, vpn = false,
                probeRoute = { model -> probed += model.optString("id"); true }
            )

            assertEquals("routeway", selected?.optString("id"))
            assertTrue(probed.isEmpty())
            assertFalse(RouteMeta.route(root, "routeway").optJSONObject("caps")?.optBoolean("vision") == true)
            assertEquals(0, RouteMeta.route(root, "routeway").optInt("day_count"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class ShelfImageDescribedTest {
    @Test fun shelfImageIsDescribedAndNeverPassedThroughTextIngestion() {
        val root = Files.createTempDirectory("ruri-shelf-image").toFile()
        try {
            FileManager.init(root)
            val file = java.io.File(root, "shelf/photo.png")
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 0, 0, 0, 0))
            val query = ShelfWatcher.ingestFile(root, "photo.png") { "Красный велосипед у стены" }
            assertNull(query)
            assertEquals("Красный велосипед у стены",
                FileManager.read(root, "shelf/.desc/photo.png.txt"))
            val events = FileManager.readLastLines(root, "memory/L0_raw.jsonl", 20).map { JSONObject(it) }
            assertTrue(events.any { it.optString("type") == "shelf_image_described" })
            assertFalse(events.any { it.optString("type") == "shelf_file_ingested" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun unavailableVisionWritesAuditableUnreadableEvent() {
        val root = Files.createTempDirectory("ruri-shelf-unreadable").toFile()
        try {
            FileManager.init(root)
            val file = java.io.File(root, "shelf/scan.png")
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 0, 0, 0, 0))
            ShelfWatcher.ingestFile(root, "scan.png") { null }
            val event = FileManager.readLastLines(root, "memory/L0_raw.jsonl", 20)
                .map { JSONObject(it) }.single { it.optString("type") == "shelf_image_unreadable" }
            assertEquals("vision route unavailable", event.optString("reason"))
            assertFalse(FileManager.read(root, "shelf/.desc/scan.png.txt").isNotBlank())
        } finally {
            root.deleteRecursively()
        }
    }
}

class VisionHonestUnavailableTest {
    @Test fun imageWithoutDescriptionGetsExplicitPlaceholderAndHonestyPrompt() {
        val root = Files.createTempDirectory("ruri-vision-unavailable").toFile()
        try {
            FileManager.init(root)
            val file = java.io.File(root, "shelf/unknown.png")
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 0, 0, 0, 0))
            assertEquals("[изображение без описания: vision недоступен]",
                ChatEngine.shelfText(root, "unknown.png"))
            assertTrue(Capabilities.bodySection(root).contains("Если vision-маршрут недоступен"))
        } finally {
            root.deleteRecursively()
        }
    }
}
