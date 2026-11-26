package com.ruri

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI

/**
 * Opens a public web page and returns its readable text.
 *
 * Deliberately conservative: only http(s), only public addresses (no localhost, LAN, cloud
 * metadata), bounded size and redirects, scripts and markup stripped. The model asks for a
 * page; this decides whether that page is safe to fetch.
 *
 * There is no site crawling or search engine here: the Commander supplies the address.
 */
object UrlReader {
    private val deny = Regex("(?i)(csam|child[\\s_-]*porn|underage[\\s_-]*sex|minor[\\s_-]*nude)")
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_CHARS = 12_000
    private const val MAX_REDIRECTS = 5

    internal fun isDeniedUrl(url: String): Boolean = deny.containsMatchIn(url)

    /** Local, link-local, private and metadata ranges must never be fetched by the model. */
    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
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
        return when {
            a == 0 || a == 10 || a == 127 || a >= 224 -> false
            a == 100 && b in 64..127 -> false      // CGNAT 100.64/10
            a == 169 && b == 254 -> false          // link-local / cloud metadata
            a == 172 && b in 16..31 -> false
            a == 192 && b == 168 -> false
            a == 192 && b == 0 && (bytes[2].toInt() and 0xff) == 0 -> false
            a == 198 && b in 18..19 -> false
            else -> true
        }
    }

    internal fun isPublicHost(host: String): Boolean {
        val normalized = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (normalized.isBlank() || normalized == "localhost" ||
            normalized.endsWith(".localhost") || normalized.endsWith(".local") ||
            normalized.endsWith(".internal") || normalized.endsWith(".home.arpa")
        ) return false
        return runCatching {
            val addresses = InetAddress.getAllByName(normalized)
            addresses.isNotEmpty() && addresses.all(::isPublicAddress)
        }.getOrDefault(false)
    }

    /** Returns (ok, text). Never throws: every failure becomes a human-readable message. */
    fun read(root: File, url: String): Pair<Boolean, String> {
        val initial = runCatching { URI(url.trim()) }.getOrNull()
            ?: return false to "Недопустимый URL"
        val scheme = initial.scheme?.lowercase() ?: return false to "Недопустимый URL"
        if (scheme != "http" && scheme != "https" || initial.rawUserInfo != null || initial.host.isNullOrBlank()) {
            return false to "Недопустимый URL"
        }
        if (isDeniedUrl(url)) return false to "URL заблокирован"
        if (!isPublicHost(initial.host)) return false to "Адрес локальный или служебный — читать нельзя"

        var target = initial
        var connection: HttpURLConnection? = null
        return try {
            repeat(MAX_REDIRECTS + 1) { hop ->
                connection = (target.toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12_000
                    readTimeout = 18_000
                    instanceFollowRedirects = false
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/122 Mobile Safari/537.36"
                    )
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                }
                val code = connection!!.responseCode
                if (code in 300..399) {
                    val location = connection!!.getHeaderField("Location")
                        ?: return false to "Перенаправление без адреса"
                    val next = target.resolve(location)
                    val nextScheme = next.scheme?.lowercase()
                    // Never downgrade https to http, and re-check the redirect target.
                    if ((nextScheme != "http" && nextScheme != "https") ||
                        (target.scheme.equals("https", true) && !nextScheme.equals("https", true))
                    ) {
                        return false to "Недопустимое перенаправление"
                    }
                    if (next.host.isNullOrBlank() || !isPublicHost(next.host)) {
                        return false to "Перенаправление ведёт на локальный адрес"
                    }
                    connection!!.disconnect()
                    connection = null
                    target = next
                    if (hop == MAX_REDIRECTS) return false to "Слишком много перенаправлений"
                    return@repeat
                }
                if (code !in 200..299) return false to "HTTP $code"
                val mime = connection!!.contentType?.substringBefore(';')?.trim().orEmpty()
                if (connection!!.contentLengthLong > MAX_BYTES) return false to "Страница слишком большая"
                val bytes = readBounded(connection!!, MAX_BYTES) ?: return false to "Страница слишком большая"
                return contentResult(root, mime, bytes)
            }
            false to "Слишком много перенаправлений"
        } catch (e: Exception) {
            false to (e.message ?: "Ошибка чтения")
        } finally {
            connection?.disconnect()
        }
    }

    internal fun contentResult(root: File, mime: String, bytes: ByteArray): Pair<Boolean, String> {
        val raw = bytes.toString(Charsets.UTF_8)
        val looksHtml = mime.contains("html", ignoreCase = true) ||
            (!mime.contains("plain", ignoreCase = true) && Regex("(?i)<html|<body|<p[ >]").containsMatchIn(raw.take(2000)))
        val text = if (looksHtml) extractHtml(raw) else raw
        val bounded = text.take(MAX_CHARS)
        return if (bounded.isBlank()) {
            // A JS-only shell or a bot block: say so instead of silently returning nothing.
            false to "На странице нет читаемого текста (только скрипты или блокировка бота)"
        } else {
            true to SecretRedactor.redactText(bounded)
        }
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

    /** Strips markup so the model receives prose, not a wall of tags. */
    internal fun extractHtml(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?is)<(nav|footer|header|aside|form|noscript).*?</\\1>", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("(?is)<br\\s*/?>"), "\n")
            .replace(Regex("(?is)</(p|div|li|h[1-6]|tr)>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace("&mdash;", "—").replace("&ndash;", "–").replace("&laquo;", "«").replace("&raquo;", "»")
        return s.replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n").trim()
    }

    /** Title of the page, used to label the source in the answer. */
    internal fun titleOf(html: String): String =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)
            ?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()?.take(120).orEmpty()
}
