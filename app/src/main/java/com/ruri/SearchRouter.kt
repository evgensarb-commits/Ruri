package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Exactly two search providers: Serper, then the user's own SearXNG instance. */
object SearchRouter {
    private const val CFG = "core/search.json"
    private val deny = Regex("(?i)(lolicon|shotacon|child\\s*porn|underage\\s*sex|minor\\s*nude)")

    private fun cfg(root: File) =
        runCatching { SecretStore.readSearchConfig(root) }.getOrElse { JSONObject() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun ok(query: String, provider: String, results: JSONArray) =
        JSONObject().put("query", query).put("provider", provider).put("results", results).toString()

    private fun err(message: String) = JSONObject().put("error", message).toString()

    private const val MAX_SEARCH_RESPONSE_BYTES = 2 * 1024 * 1024

    private fun http(
        address: String,
        method: String,
        headers: Map<String, String>,
        body: String? = null
    ): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(address).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 12000
                readTimeout = 18000
                instanceFollowRedirects = false
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                )
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", "ru,en;q=0.8")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (body != null) doOutput = true
            }
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode !in 200..299) return null
            if (connection.contentLengthLong > MAX_SEARCH_RESPONSE_BYTES) return null
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_SEARCH_RESPONSE_BYTES) return null
                    output.write(buffer, 0, count)
                }
            }
            output.toString(Charsets.UTF_8.name())
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
    private fun serper(root: File, query: String): String? {
        val key = cfg(root).optString("serper_key").trim()
        if (key.isBlank()) return null

        val raw = http(
            "https://google.serper.dev/search",
            "POST",
            mapOf("X-API-KEY" to key, "Content-Type" to "application/json"),
            JSONObject().put("q", query).put("num", 5).put("hl", "ru").toString()
        ) ?: return null

        val organic = JSONObject(raw).optJSONArray("organic") ?: return null
        val results = JSONArray()
        for (index in 0 until minOf(organic.length(), 5)) {
            val item = organic.optJSONObject(index) ?: continue
            val url = item.optString("link").trim()
            if (!url.matches(Regex("(?i)^https?://.+"))) continue
            results.put(
                JSONObject()
                    .put("title", item.optString("title"))
                    .put("url", url)
                    .put("snippet", item.optString("snippet"))
            )
        }
        return if (results.length() > 0) ok(query, "serper", results) else null
    }

    /**
     * Plain HTTP is intentionally limited to private/LAN/Tailscale addresses.
     * Public SearXNG hosts must use HTTPS.
     */
    internal fun normalizeSearxngBase(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host ?: return null
        if (host.isBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (scheme != "http" && scheme != "https") return null
        if (scheme == "http" && !isPrivateHttpHost(host)) return null
        return uri.toString().trimEnd('/').takeIf { it.isNotBlank() }
    }

    private fun isPrivateHttpHost(rawHost: String): Boolean {
        val host = rawHost.trim().lowercase(Locale.ROOT)
        if (host == "localhost" || host.endsWith(".local")) return true
        val octets = host.split('.')
        if (octets.size != 4) return false
        val parts = octets.map { it.toIntOrNull() ?: return false }
        if (parts.any { it !in 0..255 }) return false
        val a = parts[0]
        val b = parts[1]
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            a == 127 ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127)
    }

    private fun searxng(root: File, query: String): String? {
        val base = normalizeSearxngBase(cfg(root).optString("searxng_url")) ?: return null
        val raw = http(
            "$base/search?q=${enc(query)}&format=json&categories=general&safesearch=0&language=ru-RU",
            "GET",
            emptyMap()
        ) ?: return null

        val source = JSONObject(raw)
        val items = source.optJSONArray("results") ?: return null
        val results = JSONArray()
        for (index in 0 until minOf(items.length(), 5)) {
            val item = items.optJSONObject(index) ?: continue
            val url = item.optString("url").trim()
            if (!url.matches(Regex("(?i)^https?://.+"))) continue
            results.put(
                JSONObject()
                    .put("title", item.optString("title"))
                    .put("url", url)
                    .put("snippet", item.optString("content"))
            )
        }
        return if (results.length() > 0) ok(query, "searxng", results) else null
    }

    /** Returns the first non-empty, non-error search response. */
    internal fun firstSuccessful(providers: List<() -> String?>): String? {
        for (provider in providers) {
            val raw = runCatching { provider() }.getOrNull() ?: continue
            val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            if (parsed.has("error")) continue
            if ((parsed.optJSONArray("results")?.length() ?: 0) > 0) return raw
        }
        return null
    }

    internal fun isDeniedQuery(query: String): Boolean = deny.containsMatchIn(query)

    fun search(root: File, query: String): String {
        val q = SecretRedactor.redactText(query.trim())
        if (q.length < 2) return err("Слишком короткий запрос")
        if (q.length > 300) return err("Запрос слишком длинный")
        if (isDeniedQuery(q)) return err("Запрос заблокирован denylist")

        val result = firstSuccessful(
            listOf(
                { serper(root, q) },
                { searxng(root, q) }
            )
        )
        return result ?: err("Поиск недоступен: ни Serper, ни ноутбук не ответили")
    }
}
