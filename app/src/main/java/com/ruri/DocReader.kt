package com.ruri

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Text extraction for uploaded documents.
 *
 * Handles PDF, modern Word (`.docx`), OpenDocument (`.odt`), plain text and Markdown.
 * Long documents are paginated: the model receives one window at a time and must ask for the
 * next one, so a fifty-page file never floods a single request.
 *
 * Files are copied into the app's private storage first; the original URI is not retained.
 */
object DocReader {
    const val DIR = "documents"
    private const val MAX_FILE_BYTES = 12L * 1024L * 1024L
    private const val PAGE_CHARS = 6000
    private const val MAX_OFFICE_ENTRIES = 2000
    private const val MAX_OFFICE_TOTAL_UNCOMPRESSED = 64 * 1024 * 1024
    private const val MAX_OFFICE_ENTRY_BYTES = 32 * 1024 * 1024
    private const val MAX_IMAGE_ENTRY_BYTES = 8 * 1024 * 1024

    /** One stored document plus its extracted text. */
    class Document(
        val name: String,
        val pages: List<String>,
        val kind: String
    ) {
        val pageCount: Int get() = pages.size
        fun page(index: Int): String? = pages.getOrNull(index)
    }

    // ------------------------------------------------------------------ storage

    /** Copies raw bytes into private storage after validation; returns the stored name. */
    fun store(root: File, displayName: String, bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "Файл пуст" }
        require(bytes.size <= MAX_FILE_BYTES) { "Файл больше 12 МБ — читать не буду" }
        val safe = sanitizeName(displayName)
        val dir = File(root, DIR)
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, safe)
        target.writeBytes(bytes)
        return safe
    }

    /** Keeps the extension (the extractor depends on it) but drops any path component. */
    internal fun sanitizeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120)
        return cleaned.ifBlank { "document.txt" }
    }

    /** Only the stored files that are actually images. */
    fun listImageNames(root: File): List<String> =
        list(root).filter { name ->
            val file = File(File(root, DIR), name)
            file.isFile && Vision.isImage(runCatching { file.readBytes() }.getOrDefault(ByteArray(0)))
        }

    /** Only the stored files that are documents (not images). */
    fun listDocumentNames(root: File): List<String> =
        list(root).filterNot { it in listImageNames(root) }

    fun list(root: File): List<String> =
        File(root, DIR).listFiles()?.filter { it.isFile }?.map { it.name }?.sorted().orEmpty()

    fun read(root: File, name: String, page: Int = 0): Pair<Boolean, String> {
        val safe = sanitizeName(name)
        val file = File(File(root, DIR), safe)
        if (!file.isFile) return false to "Документ «$safe» не найден среди загруженных"
        return try {
            val bytes = file.readBytes()
            val text = extract(bytes, safe)
            if (text.isBlank()) return false to "В документе не нашлось читаемого текста"
            val pages = paginate(text)
            val index = page.coerceIn(0, pages.size - 1)
            val body = pages[index]
            val header = "Документ «$safe» · страница ${index + 1} из ${pages.size}"
            val footer = if (index + 1 < pages.size) {
                "\n[ПРОДОЛЖЕНИЕ: вызови read_document с name=\"$safe\" и page=${index + 1}]"
            } else {
                "\n[КОНЕЦ ДОКУМЕНТА]"
            }
            true to SecretRedactor.redactText(header + "\n" + body + footer)
        } catch (e: Exception) {
            false to "Не удалось прочитать документ: ${e.message ?: "ошибка"}"
        }
    }

    /** Splits extracted text into windows on paragraph boundaries where possible. */
    internal fun paginate(text: String, pageChars: Int = PAGE_CHARS): List<String> {
        val normalized = text.replace("\r\n", "\n").replace(Regex("\n{3,}"), "\n\n").trim()
        if (normalized.isEmpty()) return emptyList()
        if (normalized.length <= pageChars) return listOf(normalized)
        val pages = ArrayList<String>()
        var start = 0
        while (start < normalized.length) {
            var end = minOf(start + pageChars, normalized.length)
            if (end < normalized.length) {
                // Prefer a paragraph break so a page does not cut mid-sentence.
                val window = normalized.substring(start, end)
                val split = window.lastIndexOf("\n\n")
                if (split > pageChars / 2) end = start + split
                else {
                    val space = window.lastIndexOf(' ')
                    if (space > pageChars / 2) end = start + space
                }
            }
            pages.add(normalized.substring(start, end).trim())
            start = end
        }
        return pages.filter { it.isNotBlank() }
    }

    // ------------------------------------------------------------------ extraction

    /** Chooses an extractor by extension, then by content sniffing. */
    internal fun extract(bytes: ByteArray, name: String): String {
        val lower = name.lowercase()
        // A standalone image is a "document" too: it is described by the model.
        if (Vision.isImage(bytes)) return ""
        return when {
            lower.endsWith(".pdf") || looksLikePdf(bytes) -> readPdf(bytes)
            lower.endsWith(".docx") -> readZipXml(bytes, "word/document.xml", isOdt = false)
            lower.endsWith(".odt") -> readZipXml(bytes, "content.xml", isOdt = true)
            else -> decodeText(bytes)
        }
    }

    internal fun looksLikePdf(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()

    /**
     * Minimal PDF text extractor.
     *
     * Reads content streams, inflates FlateDecode ones, and pulls text out of the `Tj`/`TJ`
     * operators. It is not a layout engine: columns and tables come out as a stream of words,
     * and text stored as images cannot be recovered (no OCR). Good enough to read a document.
     */
    internal fun readPdf(bytes: ByteArray): String {
        val builder = StringBuilder()
        // Text lives inside `stream ... endstream` blocks; each is inflated when compressed.
        var index = 0
        while (true) {
            val streamStart = indexOf(bytes, "stream".toByteArray(), index)
            if (streamStart < 0) break
            var dataStart = streamStart + "stream".length
            if (dataStart < bytes.size && bytes[dataStart] == 13.toByte()) dataStart++
            if (dataStart < bytes.size && bytes[dataStart] == 10.toByte()) dataStart++
            val streamEnd = indexOf(bytes, "endstream".toByteArray(), dataStart)
            if (streamEnd < 0) break
            val raw = bytes.copyOfRange(dataStart, streamEnd)
            val inflated = inflateOrNull(raw) ?: raw
            extractPdfTextOperators(inflated, builder)
            if (builder.length > 2_000_000) break
            index = streamEnd + "endstream".length
        }
        return builder.toString()
    }

    private fun inflateOrNull(raw: ByteArray): ByteArray? = runCatching {
        val inflater = java.util.zip.Inflater()
        try {
            inflater.setInput(raw)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                    continue
                }
                output.write(buffer, 0, count)
                if (output.size() > MAX_OFFICE_ENTRY_BYTES) break
            }
            output.toByteArray().takeIf { it.isNotEmpty() }
        } finally {
            inflater.end()
        }
    }.getOrNull()

    /** Extracts literal strings from `(text) Tj` and `[(a)(b)] TJ` operators. */
    internal fun extractPdfTextOperators(stream: ByteArray, out: StringBuilder) {
        val text = stream.toString(Charsets.ISO_8859_1)
        val pattern = Regex("\\(((?:\\\\.|[^\\\\()])*)\\)\\s*(Tj|TJ|')")
        for (match in pattern.findAll(text)) {
            out.append(unescapePdf(match.groupValues[1]))
        }
        // Newlines come from T* / Td operators; approximate them as line breaks.
        if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
    }

    private fun unescapePdf(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n' -> { out.append('\n'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    '(', ')', '\\' -> { out.append(next); i += 2 }
                    else -> {
                        // Octal escape \ddd
                        val octal = value.substring(i + 1, minOf(i + 4, value.length))
                            .takeWhile { it in '0'..'7' }
                        if (octal.isNotEmpty()) {
                            out.append(octal.toInt(8).toChar())
                            i += 1 + octal.length
                        } else { out.append(next); i += 2 }
                    }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** DOCX / ODT: one XML part inside a zip, namespaced paragraphs become lines. */
    internal fun readZipXml(bytes: ByteArray, target: String, isOdt: Boolean): String {
        if (bytes.size > MAX_FILE_BYTES) return ""
        val xmlBytes = runCatching {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                var entryCount = 0
                var totalUncompressed = 0
                while (entry != null) {
                    entryCount++
                    if (entryCount > MAX_OFFICE_ENTRIES) return@use null
                    val isTarget = entry.name == target
                    val output = if (isTarget) ByteArrayOutputStream() else null
                    val buffer = ByteArray(8192)
                    var entryBytes = 0
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        entryBytes += count
                        totalUncompressed += count
                        if (totalUncompressed > MAX_OFFICE_TOTAL_UNCOMPRESSED) return@use null
                        if (isTarget && entryBytes > MAX_OFFICE_ENTRY_BYTES) return@use null
                        output?.write(buffer, 0, count)
                    }
                    if (isTarget) return@use output?.toByteArray()
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                null
            }
        }.getOrNull() ?: return ""

        return runCatching {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            // No external entities: a crafted document must not read local files.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            val document = factory.newDocumentBuilder().parse(xmlBytes.inputStream())
            val text = StringBuilder()
            val nodes = document.getElementsByTagName(if (isOdt) "text:p" else "w:p")
            for (i in 0 until nodes.length) {
                val paragraph = nodes.item(i)?.textContent?.trim().orEmpty()
                if (paragraph.isNotEmpty()) text.append(paragraph).append('\n')
            }
            text.append(if (isOdt) OdtTables.textOf(document) else DocxTables.textOf(document))
            text.toString()
        }.getOrDefault("")
    }

    /**
     * Media entries inside a DOCX/ODT archive, so pictures in a document can be described.
     * Bounded by the same zip limits as text extraction.
     */
    internal fun embeddedImages(bytes: ByteArray, limit: Int = 6): List<Pair<String, ByteArray>> {
        val found = ArrayList<Pair<String, ByteArray>>()
        runCatching {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                var count = 0
                var total = 0
                while (entry != null && found.size < limit) {
                    count++
                    if (count > MAX_OFFICE_ENTRIES) break
                    val name = entry.name.lowercase()
                    val isImage = name.startsWith("word/media/") || name.startsWith("pictures/") ||
                        name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                        name.endsWith(".webp") || name.endsWith(".gif")
                    val output = if (isImage) ByteArrayOutputStream() else null
                    val buffer = ByteArray(8192)
                    var entryBytes = 0
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        total += read
                        if (total > MAX_OFFICE_TOTAL_UNCOMPRESSED) return@use
                        if (output != null && entryBytes > MAX_IMAGE_ENTRY_BYTES) break
                        output?.write(buffer, 0, read)
                    }
                    if (output != null) {
                        val data = output.toByteArray()
                        if (Vision.isImage(data)) found.add(entry.name to data)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return found
    }

    internal fun decodeText(bytes: ByteArray): String {
        if (looksBinary(bytes)) return ""
        return bytes.toString(Charsets.UTF_8)
            .ifBlank { bytes.toString(Charsets.ISO_8859_1) }
    }

    /** A rough binary check: control characters other than whitespace mean "not text". */
    internal fun looksBinary(bytes: ByteArray): Boolean {
        val sample = bytes.take(4096)
        if (sample.isEmpty()) return true
        var control = 0
        for (byte in sample) {
            val value = byte.toInt() and 0xff
            if (value == 0) return true
            if (value < 9 || (value in 14..31)) control++
        }
        return control * 100 / sample.size > 10
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        var i = from.coerceAtLeast(0)
        while (i <= haystack.size - needle.size) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) { match = false; break }
            }
            if (match) return i
            i++
        }
        return -1
    }
}

/** Table text for DOCX: cell contents concatenated per row. */
internal object DocxTables {
    fun textOf(document: org.w3c.dom.Document): String {
        val rows = document.getElementsByTagName("w:tr")
        if (rows.length == 0) return ""
        val out = StringBuilder()
        for (i in 0 until rows.length) {
            val cells = rows.item(i)?.let { row ->
                runCatching {
                    val list = (row as org.w3c.dom.Element).getElementsByTagName("w:tc")
                    (0 until list.length).joinToString(" | ") { index ->
                        list.item(index)?.textContent?.trim().orEmpty()
                    }
                }.getOrNull()
            }
            if (!cells.isNullOrBlank()) out.append(cells).append('\n')
        }
        return out.toString()
    }
}

/** Table text for ODT. */
internal object OdtTables {
    fun textOf(document: org.w3c.dom.Document): String {
        val rows = document.getElementsByTagName("table:table-row")
        if (rows.length == 0) return ""
        val out = StringBuilder()
        for (i in 0 until rows.length) {
            val cells = rows.item(i)?.let { row ->
                runCatching {
                    val list = (row as org.w3c.dom.Element).getElementsByTagName("table:table-cell")
                    (0 until list.length).joinToString(" | ") { index ->
                        list.item(index)?.textContent?.trim().orEmpty()
                    }
                }.getOrNull()
            }
            if (!cells.isNullOrBlank()) out.append(cells).append('\n')
        }
        return out.toString()
    }
}
