package com.ruri

import android.content.Context
import android.os.FileObserver
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Watches the private shelf directory and queues durable ingestion events. */
object ShelfWatcher {
    private const val SEEN = "memory/shelf_seen.json"
    private var observer: FileObserver? = null
    private val lock = Any()

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        val dir = File(app.filesDir, "shelf").apply { mkdirs() }
        stop()
        observer = object : FileObserver(dir, FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null) markAdded(app, path)
            }
        }.also { it.startWatching() }
        scan(app)
    }

    fun stop() {
        observer?.stopWatching()
        observer = null
    }

    fun markAdded(ctx: Context, name: String) = synchronized(lock) {
        val safe = safeName(name) ?: return
        val root = ctx.filesDir
        val seen = readSeen(root)
        if (seen.optBoolean(safe)) return
        seen.put(safe, true)
        FileManager.write(root, SEEN, seen.toString(2))
        LifeScheduler.enqueueEvent(ctx.applicationContext, "shelf_file_added:" + safe)
    }

    fun scan(ctx: Context) {
        val dir = File(ctx.filesDir, "shelf")
        if (!dir.exists()) dir.mkdirs()
        dir.listFiles()?.filter { it.isFile }?.forEach { markAdded(ctx, it.name) }
    }

    /** Converts queued files into auditable L0 records. Images are never ingested as UTF-8 text. */
    fun ingestPending(ctx: Context, pending: List<JSONObject>) {
        val root = ctx.filesDir
        pending.forEach { queued ->
            val reason = queued.optString("reason")
            if (!reason.startsWith("shelf_file_added:")) return@forEach
            val name = safeName(reason.removePrefix("shelf_file_added:")) ?: return@forEach
            val query = runCatching { ingestFile(root, name) }
                .onFailure { Log.w("Ruri", "Could not ingest shelf file " + name, it) }
                .getOrNull()
            if (!query.isNullOrBlank()) LifeScheduler.enqueueEvent(ctx, "research_task:" + query)
        }
    }

    internal fun ingestFile(
        root: File,
        name: String,
        describeImage: (String) -> String? = { Vision.describeShelfFile(root, it) }
    ): String? {
        val safe = safeName(name) ?: return null
        val file = File(root, "shelf/" + safe)
        if (!file.isFile) return null
        val bytes = file.readBytes()
        val mime = Vision.mimeOf(bytes)
        if (mime != null) {
            val description = runCatching { describeImage(safe) }.getOrNull()?.trim()?.take(4000)
            if (!description.isNullOrBlank()) {
                val safeDescription = SecretRedactor.redactText(description).take(4000)
                FileManager.write(root, "shelf/.desc/" + safe + ".txt", safeDescription)
                FileManager.appendEvent(root, JSONObject()
                    .put("type", "shelf_image_described").put("source", "shelf")
                    .put("name", safe).put("size_bytes", bytes.size)
                    .put("mime", mime).put("text", safeDescription).put("extractor", "vision")
                    .put("ts", System.currentTimeMillis()))
            } else {
                FileManager.appendEvent(root, JSONObject()
                    .put("type", "shelf_image_unreadable").put("source", "shelf")
                    .put("name", safe).put("size_bytes", bytes.size)
                    .put("mime", mime).put("reason", "vision route unavailable")
                    .put("ts", System.currentTimeMillis()))
            }
            return null
        }

        val lower = safe.lowercase()
        if (lower.endsWith(".docx") || lower.endsWith(".odt")) {
            val officeText = SecretRedactor.redactText(extractOffice(bytes, lower)).take(12000)
            if (officeText.isNotBlank()) {
                val id = "shelf-" + file.name.hashCode() + "-" + file.lastModified()
                FileManager.appendEvent(root, JSONObject()
                    .put("id", id).put("ts", System.currentTimeMillis())
                    .put("type", "shelf_file_ingested").put("source", "shelf")
                    .put("name", safe).put("size_bytes", bytes.size)
                    .put("text", officeText).put("extractor", "office_xml"))
                return (safe.substringBeforeLast('.', safe) + " " + officeText.take(180))
                    .trim().take(280).takeIf { it.length >= 2 }
            }
        }

        val text = decodeText(bytes).take(12000)
        val id = "shelf-" + file.name.hashCode() + "-" + file.lastModified()
        FileManager.appendEvent(root, JSONObject()
            .put("id", id).put("ts", System.currentTimeMillis())
            .put("type", "shelf_file_ingested").put("source", "shelf")
            .put("name", safe).put("size_bytes", bytes.size)
            .put("text", text).put("extractor", if (text.isBlank()) "metadata_only" else "utf8_text"))
        val query = (safe.substringBeforeLast('.', safe) + " " + text.take(180)).trim().take(280)
        return query.takeIf { it.length >= 2 }
    }

    private fun readSeen(root: File): JSONObject =
        runCatching { JSONObject(FileManager.read(root, SEEN)) }.getOrDefault(JSONObject())

    internal fun safeName(value: String): String? {
        val name = value.substringAfterLast('/').substringAfterLast('\\')
        if (name.isBlank() || name == "." || name == ".." || name.length > 120) return null
        return name
    }

    /**
     * Extracts paragraph text from DOCX/ODT ZIP containers without third-party libraries.
     * The uncompressed XML is bounded to avoid pathological ZIP entries.
     */
    internal fun extractOffice(bytes: ByteArray, name: String): String {
        val target = if (name.endsWith(".odt", ignoreCase = true)) "content.xml" else "word/document.xml"
        val xmlBytes = runCatching {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == target) {
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 2 * 1024 * 1024) return@use null
                            output.write(buffer, 0, count)
                        }
                        return@use output.toByteArray()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                null
            }
        }.getOrNull() ?: return ""

        return runCatching {
            val parser = android.util.Xml.newPullParser()
            parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(xmlBytes.inputStream(), "UTF-8")
            val text = StringBuilder()
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (event) {
                    org.xmlpull.v1.XmlPullParser.TEXT,
                    org.xmlpull.v1.XmlPullParser.CDSECT -> text.append(parser.text)
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                        "tab" -> text.append('\t')
                        "br" -> text.append('\n')
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> if (
                        parser.name.substringAfter(':') == "p"
                    ) text.append('\n')
                }
                event = parser.next()
            }
            text.toString()
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex(" *\\n *"), "\n")
                .replace(Regex("\\n{2,}"), "\n")
                .trim()
        }.getOrDefault("")
    }

    internal fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sample = bytes.take(minOf(bytes.size, 4096))
        val oddZeroes = sample.indices.count { it % 2 == 1 && sample[it].toInt() == 0 }
        val evenZeroes = sample.indices.count { it % 2 == 0 && sample[it].toInt() == 0 }
        val decoded = when {
            oddZeroes > sample.size * 0.2 -> runCatching { String(bytes, Charsets.UTF_16LE) }.getOrDefault("")
            evenZeroes > sample.size * 0.2 -> runCatching { String(bytes, Charsets.UTF_16BE) }.getOrDefault("")
            else -> runCatching { bytes.toString(Charsets.UTF_8) }.getOrDefault("")
        }
        val printable = decoded.count { it == '\n' || it == '\r' || it == '\t' || !it.isISOControl() }
        if (decoded.isNotBlank() && printable >= decoded.length * 0.85) return decoded
        // Recover readable strings from partially textual binary formats (e.g. PDF metadata/content streams).
        return Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{P}\\p{Zs}]{5,}")
            .findAll(decoded).map { it.value.trim() }.filter { it.length >= 6 }
            .take(120).joinToString("\n").take(12000)
    }
}