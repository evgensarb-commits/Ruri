package com.ruri

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Google-only web search. Uses CSE when configured, otherwise parses public Google results. */
object WebSearch {
    fun search(query: String): String = search(null, query)

    fun search(root: File?, query: String): String {
        val q = SecretRedactor.redactText(query.trim())
        if (q.length < 2) return error("Введите поисковый запрос")
        if (q.length > 300) return error("Запрос слишком длинный")
        val settings = root?.let {
            runCatching { JSONObject(FileManager.read(it, "core/search.json")) }.getOrDefault(JSONObject())
        } ?: JSONObject()
        val key = settings.optString("cse_key").trim()
        val cx = settings.optString("cse_cx").trim()
        return try {
            if (key.isNotBlank() && cx.isNotBlank()) searchCse(q, key, cx)
            else searchGoogle(q)
        } catch (e: Exception) {
            Log.w("Ruri", "Google search unavailable", e)
            error("Поиск недоступен")
        }
    }

    internal fun parseCseResponse(raw: String, query: String): String {
        val source = JSONObject(raw)
        val items = source.optJSONArray("items") ?: JSONArray()
        val results = JSONArray()
        for (i in 0 until minOf(items.length(), 7)) {
            val item = items.optJSONObject(i) ?: continue
            val url = item.optString("link")
            if (!url.startsWith("https://") && !url.startsWith("http://")) continue
            results.put(JSONObject().put("title", clean(item.optString("title")))
                .put("url", url).put("snippet", clean(item.optString("snippet"))))
        }
        return JSONObject().put("query", query).put("results", results).toString()
    }

    internal fun parseGoogleHtml(html: String, query: String): String {
        val lower = html.lowercase()
        if (lower.contains("/sorry/") || lower.contains("unusual traffic") ||
            lower.contains("our systems have detected") || lower.contains("captcha")) {
            return error("Поиск недоступен")
        }
        val results = JSONArray()
        val anchors = Regex("(?is)<a[^>]+href=[\\\"']([^\\\"']+)[\\\"'][^>]*>(.*?)</a>").findAll(html)
        for (anchor in anchors) {
            if (results.length() >= 7) break
            val href = decode(anchor.groupValues[1])
            val target = when {
                href.startsWith("/url?") -> Regex("[?&]q=([^&]+)").find(href)?.groupValues?.get(1)?.let(::decode) ?: ""
                href.startsWith("https://") || href.startsWith("http://") -> href
                else -> ""
            }
            if (target.isBlank() || target.contains("google.com/")) continue
            val title = Regex("(?is)<h[23][^>]*>(.*?)</h[23]>").find(anchor.groupValues[2])?.groupValues?.get(1)
                ?.let(::clean).orEmpty()
            if (title.isBlank()) continue
            val snippet = clean(anchor.groupValues[2]).take(500)
            results.put(JSONObject().put("title", title).put("url", target).put("snippet", snippet))
        }
        if (results.length() == 0) return error("Поиск недоступен")
        return JSONObject().put("query", query).put("results", results).toString()
    }

    private fun searchCse(query: String, key: String, cx: String): String {
        val url = "https://www.googleapis.com/customsearch/v1?key=" + enc(key) +
            "&cx=" + enc(cx) + "&q=" + enc(query)
        val raw = request(url)
        val parsed = parseCseResponse(raw, query)
        if (JSONObject(parsed).optJSONArray("results")?.length() == 0) return error("Поиск недоступен")
        return parsed
    }

    private fun searchGoogle(query: String): String {
        val url = "https://www.google.com/search?q=" + enc(query) + "&num=10"
        return parseGoogleHtml(request(url), query)
    }

    private fun request(address: String): String {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(address).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 18000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                setRequestProperty("Accept-Language", "ru,en;q=0.8")
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { connection?.disconnect() }
    }

    private fun error(message: String) = JSONObject().put("error", message).toString()
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun decode(value: String) = runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    private fun clean(value: String): String = decode(value).replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace(Regex("\\s+"), " ").trim()
}