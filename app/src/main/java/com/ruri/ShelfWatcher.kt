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
        observer = object : FileObserver(dir, CLOSE_WRITE or MOVED_TO) {
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

    /** Converts queued file events into bounded, auditable L0 records. */
    fun ingestPending(ctx: Context, pending: List<JSONObject>) {
        val root = ctx.filesDir
        pending.forEach { queued ->
            val reason = queued.optString("reason")
            if (!reason.startsWith("shelf_file_added:")) return@forEach
            val name = safeName(reason.removePrefix("shelf_file_added:")) ?: return@forEach
            val file = File(root, "shelf/" + name)
            if (!file.isFile) return@forEach
            try {
                val bytes = file.readBytes()
                val text = decodeText(bytes).take(12000)
                val id = "shelf-" + file.name.hashCode() + "-" + file.lastModified()
                FileManager.appendEvent(root, JSONObject()
                    .put("id", id).put("ts", System.currentTimeMillis())
                    .put("type", "shelf_file_ingested").put("source", "shelf")
                    .put("name", name).put("size_bytes", bytes.size)
                    .put("text", text).put("extractor", if (text.isBlank()) "metadata_only" else "utf8_text"))
                val query = (name.substringBeforeLast('.', name) + " " + text.take(180)).trim().take(280)
                if (query.length >= 2) LifeScheduler.enqueueEvent(ctx, "research_task:" + query)
            } catch (e: Exception) {
                Log.w("Ruri", "Could not ingest shelf file " + name, e)
            }
        }
    }

    private fun readSeen(root: File): JSONObject =
        runCatching { JSONObject(FileManager.read(root, SEEN)) }.getOrDefault(JSONObject())

    private fun safeName(value: String): String? {
        val name = value.substringAfterLast('/').substringAfterLast('\\')
        if (name.isBlank() || name == "." || name == ".." || name.length > 120) return null
        return name
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sample = bytes.take(minOf(bytes.size, 4096))
        if (sample.any { (it.toInt() and 0xff) == 0 }) return ""
        val decoded = runCatching { bytes.toString(Charsets.UTF_8) }.getOrDefault("")
        val printable = decoded.count { it == '\n' || it == '\r' || it == '\t' || !it.isISOControl() }
        return if (printable >= decoded.length * 0.85) decoded else ""
    }
}