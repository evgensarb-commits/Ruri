package com.ruri

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UrlReader {
    private val deny = Regex("(?i)(csam|child[\\s_-]*porn|underage[\\s_-]*sex|minor[\\s_-]*nude)")
    private const val MAX_BYTES = 8 * 1024 * 1024

    internal fun isDeniedUrl(url: String): Boolean = deny.containsMatchIn(url)

    fun read(root: File, url: String): Pair<Boolean, String> {
        val u = url.trim()
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) return false to "Недопустимый URL"
        if (isDeniedUrl(u)) return false to "URL заблокирован denylist"
        var c: HttpURLConnection? = null
        return try {
            c = URL(u).openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.connectTimeout = 12000
            c.readTimeout = 18000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            if (c.responseCode !in 200..299) return false to ("HTTP " + c.responseCode)
            val mime = c.contentType?.substringBefore(';')?.trim().orEmpty()
            if (c.contentLengthLong > MAX_BYTES) return false to "Страница слишком большая"
            val bytes = readBounded(c, MAX_BYTES) ?: return false to "Страница слишком большая"
            return contentResult(root, mime, bytes)
        } catch (e: Exception) {
            false to (e.message ?: "Ошибка чтения")
        } finally {
            c?.disconnect()
        }
    }

    /** Shared response handling; injectable Vision callback keeps image routing testable without network calls. */
    internal fun contentResult(
        root: File,
        mime: String,
        bytes: ByteArray,
        describeImage: (File, ByteArray, String, String) -> String? =
            { dir, data, type, prompt -> Vision.describe(dir, data, type, prompt) }
    ): Pair<Boolean, String> {
        val detectedMime = mime.takeIf { it.startsWith("image/", ignoreCase = true) }
            ?: Vision.mimeOf(bytes)
        if (detectedMime != null) {
            val description = describeImage(
                root, bytes, detectedMime,
                "Опиши изображение подробно: объекты, люди, текст, стиль, настроение. Не выдумывай детали."
            )
            return if (!description.isNullOrBlank()) {
                true to SecretRedactor.redactText(description).take(4000)
            } else {
                false to "Изображение получено, но vision-маршрут недоступен"
            }
        }

        val raw = bytes.toString(Charsets.UTF_8)
        val text = if (mime.contains("html", ignoreCase = true)) extractHtml(raw) else raw
        val bounded = text.take(12000)
        return if (bounded.isBlank()) false to "Пустая страница (JS-оболочка или bot-блок)"
        else true to SecretRedactor.redactText(bounded)
    }

    private fun readBounded(connection: HttpURLConnection, maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxBytes) return null
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    internal fun extractHtml(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?is)<(nav|footer|header|aside).*?</\\1>"), " ")
        s = s.replace(Regex("(?is)<br\\s*/?>"), "\n")
            .replace(Regex("(?is)</p>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        return s.replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n").trim()
    }
}
