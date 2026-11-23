package com.ruri

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL

object UrlReader {
    private val deny = Regex("(?i)(csam|child[\\s_-]*porn|underage[\\s_-]*sex|minor[\\s_-]*nude)")
    private const val MAX_BYTES = 8 * 1024 * 1024

    internal fun isDeniedUrl(url: String): Boolean = deny.containsMatchIn(url)

    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        if (bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            if ((first and 0xfe) == 0xfc) return false // IPv6 unique-local fc00::/7
            if (first == 0xfe && (second and 0xc0) == 0x80) return false // fe80::/10
            val mappedV4 = bytes.take(10).all { it.toInt() == 0 } &&
                (bytes[10].toInt() and 0xff) == 0xff && (bytes[11].toInt() and 0xff) == 0xff
            if (mappedV4) {
                return isPublicAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
            }
            return true
        }
        if (bytes.size != 4) return false
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        val c = bytes[2].toInt() and 0xff
        return when {
            a == 0 || a == 10 || a == 127 || a >= 224 -> false
            a == 100 && b in 64..127 -> false
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && (b == 168 || (b == 0 && c == 0) || (b == 0 && c == 2)) -> false
            a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)) -> false
            a == 203 && b == 0 && c == 113 -> false
            else -> true
        }
    }

    internal fun isPublicHost(host: String): Boolean {
        val normalized = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (normalized.isBlank() || normalized == "localhost" || normalized.endsWith(".localhost") ||
            normalized.endsWith(".local") || normalized.endsWith(".internal") || normalized.endsWith(".home.arpa")) return false
        return runCatching {
            val addresses = InetAddress.getAllByName(normalized)
            addresses.isNotEmpty() && addresses.all(::isPublicAddress)
        }.getOrDefault(false)
    }

    fun read(root: File, url: String): Pair<Boolean, String> {
        val initial = runCatching { URI(url.trim()) }.getOrNull()
            ?: return false to "Недопустимый URL"
        val scheme = initial.scheme?.lowercase() ?: return false to "Недопустимый URL"
        if (scheme != "http" && scheme != "https" || initial.rawUserInfo != null || initial.host.isNullOrBlank()) {
            return false to "Недопустимый URL"
        }
        if (isDeniedUrl(url)) return false to "URL заблокирован denylist"
        var target = initial
        var c: HttpURLConnection? = null
        return try {
            repeat(6) { hop ->
                if (!isPublicHost(target.host)) return false to "URL ведёт на локальный или служебный адрес"
                c = target.toURL().openConnection() as HttpURLConnection
                c!!.apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 18000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                }
                val code = c!!.responseCode
                if (code in 300..399) {
                    val location = c!!.getHeaderField("Location")
                        ?: return false to "Перенаправление без адреса"
                    val next = target.resolve(location)
                    val nextScheme = next.scheme?.lowercase()
                    if ((nextScheme != "http" && nextScheme != "https") ||
                        (target.scheme.equals("https", true) && !nextScheme.equals("https", true))) {
                        return false to "Недопустимое перенаправление"
                    }
                    c!!.disconnect()
                    c = null
                    target = next
                    if (hop == 5) return false to "Слишком много перенаправлений"
                    return@repeat
                }
                if (code !in 200..299) return false to ("HTTP " + code)
                val mime = c!!.contentType?.substringBefore(';')?.trim().orEmpty()
                if (c!!.contentLengthLong > MAX_BYTES) return false to "Страница слишком большая"
                val bytes = readBounded(c!!, MAX_BYTES) ?: return false to "Страница слишком большая"
                return contentResult(root, mime, bytes)
            }
            false to "Слишком много перенаправлений"
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
