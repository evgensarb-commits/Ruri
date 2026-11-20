package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Search through free configured providers, then public fallbacks. */
object SearchRouter {
    private const val CFG = "core/search.json"

    private fun cfg(root: File) = runCatching { JSONObject(FileManager.read(root, CFG)) }.getOrElse { JSONObject() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.connectTimeout = 12000
            c.readTimeout = 18000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            c.setRequestProperty("Accept-Language", "ru,en;q=0.8")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    private fun ok(query: String, provider: String, arr: JSONArray) =
        JSONObject().put("query", query).put("provider", provider).put("results", arr).toString()

    private fun empty() = JSONObject().put("error", "Поиск недоступен").toString()

    internal fun firstSuccessful(providers: List<() -> String?>): String? {
        for (provider in providers) {
            val raw = runCatching { provider() }.getOrNull() ?: continue
            val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            if (parsed.has("error")) continue
            if ((parsed.optJSONArray("results")?.length() ?: 0) > 0) return raw
        }
        return null
    }

    private fun brave(root: File, q: String): String? {
        val key = cfg(root).optString("brave_key").trim()
        if (key.isBlank()) return null
        val raw = get(
            "https://api.search.brave.com/res/v1/web/search?q=" + enc(q) + "&count=5",
            mapOf("X-Subscription-Token" to key)
        ) ?: return null
        val arr = JSONObject(raw).optJSONObject("web")?.optJSONArray("results") ?: return null
        val out = JSONArray()
        for (i in 0 until minOf(arr.length(), 5)) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (!url.matches(Regex("(?i)^https?://.+"))) continue
            out.put(JSONObject().put("title", o.optString("title"))
                .put("url", url).put("snippet", o.optString("description")))
        }
        return if (out.length() > 0) ok(q, "brave", out) else null
    }

    private fun yandex(root: File, q: String): String? {
        val c = cfg(root)
        val user = c.optString("yandex_user").trim()
        val key = c.optString("yandex_key").trim()
        if (user.isBlank() || key.isBlank()) return null
        val raw = get(
            "https://yandex.ru/search/xml?user=" + enc(user) + "&key=" + enc(key) +
                "&query=" + enc(q) + "&l10n=ru&sortby=rlv&filter=moderate&groupby=attr%3D%22%22.mode%3Dflat.groups-on-page%3D5.docs-in-group%3D1"
        ) ?: return null
        val out = JSONArray()
        for (m in Regex("(?is)<doc>(.*?)</doc>").findAll(raw)) {
            val d = m.groupValues[1]
            val url = Regex("(?is)<url>(.*?)</url>").find(d)?.groupValues?.get(1)?.trim().orEmpty()
            val title = Regex("(?is)<title>(.*?)</title>").find(d)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty()
            val snip = Regex("(?is)<passage>(.*?)</passage>").find(d)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty()
            if (url.matches(Regex("(?i)^https?://.+"))) {
                out.put(JSONObject().put("title", title).put("url", url).put("snippet", snip))
            }
            if (out.length() >= 5) break
        }
        return if (out.length() > 0) ok(q, "yandex", out) else null
    }

    private fun wikipedia(q: String): String? {
        val raw = get(
            "https://ru.wikipedia.org/w/api.php?action=query&list=search&srsearch=" +
                enc(q) + "&format=json&srlimit=5&utf8=1"
        ) ?: return null
        val arr = JSONObject(raw).optJSONObject("query")?.optJSONArray("search") ?: return null
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title")
            out.put(JSONObject().put("title", title)
                .put("url", "https://ru.wikipedia.org/wiki/" + enc(title.replace(' ', '_')))
                .put("snippet", o.optString("snippet").replace(Regex("<[^>]+>"), "")))
        }
        return if (out.length() > 0) ok(q, "wikipedia", out) else null
    }

    private fun googleScrape(q: String): String? {
        val raw = runCatching { WebSearch.searchGooglePublic(q) }.getOrNull() ?: return null
        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return raw.takeIf { !parsed.has("error") && (parsed.optJSONArray("results")?.length() ?: 0) > 0 }
    }

    fun search(root: File, query: String): String {
        val q = SecretRedactor.redactText(query.trim())
        if (q.length < 2) return empty()
        if (q.length > 300) return JSONObject().put("error", "Запрос слишком длинный").toString()
        val result = firstSuccessful(listOf(
            { brave(root, q) },
            { yandex(root, q) },
            { wikipedia(q) },
            { googleScrape(q) }
        ))
        return result ?: empty()
    }
}
