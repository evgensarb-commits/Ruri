package com.ruri

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object WebSearch {
    fun search(query: String): String {
        val q = query.trim()
        if (q.length < 2) return JSONObject().put("error", "Введите поисковый запрос").toString()
        if (q.length > 300) return JSONObject().put("error", "Запрос слишком длинный").toString()

        var connection: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(q, "UTF-8")
            connection = (URL("https://html.duckduckgo.com/html/?q=$encoded").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 18000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                setRequestProperty("Accept-Language", "ru,en;q=0.8")
            }
            val code = connection.responseCode
            if (code !in 200..299) return JSONObject().put("error", "Поиск вернул HTTP $code").toString()
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val results = JSONArray()
            val blocks = Regex("(?s)<div[^>]*class=\"[^\"]*result[^\"]*\"[^>]*>(.*?)</div>\\s*</div>").findAll(html)
            for (block in blocks) {
                if (results.length() >= 6) break
                val part = block.groupValues[1]
                val anchor = Regex("(?s)<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").find(part)
                    ?: continue
                val rawUrl = decodeEntities(anchor.groupValues[1])
                val title = clean(anchor.groupValues[2])
                val snippet = Regex("(?s)<(?:a|div)[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</(?:a|div)>")
                    .find(part)?.groupValues?.get(1)?.let { clean(it) } ?: ""
                val finalUrl = if (rawUrl.startsWith("//duckduckgo.com/l/")) {
                    val param = Regex("[?&]uddg=([^&]+)").find(rawUrl)?.groupValues?.get(1)
                    if (param != null) try { java.net.URLDecoder.decode(param, "UTF-8") } catch (_: Exception) { rawUrl } else rawUrl
                } else rawUrl
                if (title.isNotBlank() && finalUrl.startsWith("http")) {
                    results.put(JSONObject().put("title", title).put("url", finalUrl).put("snippet", snippet))
                }
            }
            JSONObject().put("query", q).put("results", results).toString()
        } catch (e: Exception) {
            Log.w("Ruri", "Web search failed", e)
            JSONObject().put("error", "Поиск недоступен: " + (e.message ?: e.javaClass.simpleName)).toString()
        } finally {
            connection?.disconnect()
        }
    }

    private fun clean(value: String): String = decodeEntities(value)
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ")
}