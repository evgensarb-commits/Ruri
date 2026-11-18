package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Local, self-verifying snapshots of Ruri's durable data.
 * API credentials are deliberately excluded; they remain in Android Keystore-backed storage.
 */
object LocalBackupManager {
    private const val BACKUP_DIR = "backup/automatic"
    private const val MANIFEST = "manifest.json"
    private const val MAX_BACKUPS = 14
    private const val MAX_ENTRIES = 5000
    private const val MAX_ENTRY_BYTES = 32L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 128L * 1024L * 1024L
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private val lock = Any()
    private val allowedRoots = listOf("core/", "memory/", "shelf/", "research/", "skills/", "proposals/")

    data class Verification(val valid: Boolean, val files: Map<String, ByteArray>, val error: String = "")

    fun createIfDue(root: File, now: Long = System.currentTimeMillis()): JSONObject = synchronized(lock) {
        val directory = File(root, BACKUP_DIR)
        directory.mkdirs()
        val latest = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".zip") }?.maxByOrNull { it.lastModified() }
        if (latest != null && now - latest.lastModified() < DAY_MS) {
            return@synchronized status(root).put("created", false).put("reason", "not_due")
        }
        val created = createSnapshotLocked(root, now)
        status(root).put("created", true).put("file", created.name)
    }

    fun createSnapshot(root: File, now: Long = System.currentTimeMillis()): File = synchronized(lock) {
        createSnapshotLocked(root, now)
    }

    fun status(root: File): JSONObject {
        val directory = File(root, BACKUP_DIR)
        val files = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val latest = files.firstOrNull()
        val items = JSONArray()
        files.forEach { item -> items.put(JSONObject().put("name", item.name).put("modified_at", item.lastModified()).put("size_bytes", item.length())) }
        return JSONObject()
            .put("backups", items)
            .put("directory", BACKUP_DIR)
            .put("count", files.size)
            .put("maximum", MAX_BACKUPS)
            .put("latest_file", latest?.name ?: JSONObject.NULL)
            .put("latest_at", latest?.lastModified() ?: 0L)
            .put("latest_size_bytes", latest?.length() ?: 0L)
            .put("retention_days_approx", 14)
    }

    fun verify(file: File): Verification = readAndVerify(file)

    /** Verifies every byte before changing live files. A pre-restore snapshot is created first. */
    fun restore(root: File, fileName: String, now: Long = System.currentTimeMillis()): JSONObject = synchronized(lock) {
        require(fileName.matches(Regex("ruri_auto_[0-9]{8}_[0-9]{6}_[0-9]+\\.zip"))) { "Invalid backup name" }
        val archive = File(File(root, BACKUP_DIR), fileName)
        require(archive.isFile && archive.parentFile?.canonicalFile == File(root, BACKUP_DIR).canonicalFile) {
            "Backup file not found"
        }
        val verified = readAndVerify(archive)
        require(verified.valid) { verified.error.ifBlank { "Backup verification failed" } }

        createSnapshotLocked(root, now)
        val entries = verified.files
        // Make the restored data tree match the snapshot, while preserving the separately encrypted API-key store.
        allowedRoots.forEach { relative ->
            val base = File(root, relative)
            if (base.exists()) removeFilesNotInSnapshot(root, base, entries.keys)
        }
        // Restore append-only history first; state files are then replaced atomically.
        entries["memory/L0_raw.jsonl"]?.let {
            FileManager.replaceL0FromArchive(root, it.toString(Charsets.UTF_8))
        }
        entries.keys.filter { it != "memory/L0_raw.jsonl" }.sorted().forEach { path ->
            val value = entries.getValue(path).toString(Charsets.UTF_8)
            when (path) {
                else -> FileManager.write(root, path, value)
            }
        }
        JSONObject().put("restored", true).put("files", entries.size)
            .put("source", fileName).put("pre_restore_backup_created", true)
    }

    private fun createSnapshotLocked(root: File, now: Long): File {
        val directory = File(root, BACKUP_DIR)
        if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("Cannot create backup directory")
        val captured = linkedMapOf<String, ByteArray>()
        allowedRoots.forEach { relative ->
            val base = File(root, relative)
            if (base.exists()) collectFiles(root, base, captured)
        }
        require(captured.size <= MAX_ENTRIES) { "Too many files for a backup" }
        val total = captured.values.sumOf { it.size.toLong() }
        require(total <= MAX_TOTAL_BYTES) { "Backup exceeds size limit" }

        val manifestEntries = JSONObject()
        captured.toSortedMap().forEach { (path, bytes) ->
            manifestEntries.put(path, JSONObject().put("sha256", sha256(bytes)).put("size", bytes.size))
        }
        val manifest = JSONObject()
            .put("schema_version", 1)
            .put("created_at", now)
            .put("file_count", captured.size)
            .put("total_bytes", total)
            .put("files", manifestEntries)
            .toString(2)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(now))
        val target = File(directory, "ruri_auto_${stamp}_${now}.zip")
        val temp = File(directory, target.name + ".tmp")
        try {
            ZipOutputStream(FileOutputStream(temp)).use { zip ->
                captured.toSortedMap().forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            FileOutputStream(temp, true).use { it.fd.sync() }
            if (!temp.renameTo(target)) throw IllegalStateException("Cannot finalize backup archive")
            target.setLastModified(now)
        } finally {
            if (temp.exists()) temp.delete()
        }
        prune(directory)
        return target
    }

    private fun removeFilesNotInSnapshot(root: File, file: File, included: Set<String>) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { removeFilesNotInSnapshot(root, it, included) }
            if (file != root && file.listFiles()?.isEmpty() == true) file.delete()
            return
        }
        if (!file.isFile) return
        val relative = root.toPath().relativize(file.canonicalFile.toPath()).toString()
            .replace(File.separatorChar, '/')
        if (relative == "core/secrets.enc.json") return
        if (relative !in included) {
            require(file.delete()) { "Cannot remove file absent from backup: $relative" }
        }
    }

    private fun collectFiles(root: File, file: File, output: MutableMap<String, ByteArray>) {
        if (file.isDirectory) {
            file.listFiles()?.sortedBy { it.name }?.forEach { collectFiles(root, it, output) }
            return
        }
        if (!file.isFile || file.name.endsWith(".tmp") || file.name.contains(".tmp-")) return
        val canonical = file.canonicalFile
        require(canonical.toPath().startsWith(root.canonicalFile.toPath())) { "Backup path escaped app storage" }
        val relative = root.toPath().relativize(canonical.toPath()).toString().replace(File.separatorChar, '/')
        if (!allowedRoots.any { relative.startsWith(it) }) return
        if (relative == "core/secrets.enc.json" || relative.startsWith("backup/")) return
        require(file.length() <= MAX_ENTRY_BYTES) { "Backup file too large: $relative" }
        output[relative] = file.readBytes()
    }

    private fun readAndVerify(file: File): Verification {
        return try {
            val content = linkedMapOf<String, ByteArray>()
            var manifestText: String? = null
            var total = 0L
            ZipInputStream(FileInputStream(file)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    require(!entry.isDirectory && !entry.name.startsWith("/") &&
                        !entry.name.split('/').any { it == ".." }) { "Unsafe archive entry" }
                    if (entry.name == MANIFEST) {
                        require(manifestText == null) { "Duplicate manifest" }
                        manifestText = zip.readBytesBounded(1024 * 1024).toString(Charsets.UTF_8)
                    } else {
                        require(allowedRoots.any { entry.name.startsWith(it) }) { "Unexpected archive path" }
                        require(entry.name != "core/secrets.enc.json" && !entry.name.startsWith("backup/")) {
                            "Forbidden archive path"
                        }
                        require(!content.containsKey(entry.name)) { "Duplicate archive entry" }
                        val bytes = zip.readBytesBounded(MAX_ENTRY_BYTES.toInt())
                        total += bytes.size
                        require(total <= MAX_TOTAL_BYTES) { "Archive too large" }
                        content[entry.name] = bytes
                        require(content.size <= MAX_ENTRIES) { "Too many archive entries" }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val manifest = JSONObject(manifestText ?: throw IllegalArgumentException("Manifest missing"))
            require(manifest.optInt("schema_version") == 1) { "Unsupported backup version" }
            val expected = manifest.optJSONObject("files") ?: throw IllegalArgumentException("Manifest file list missing")
            require(expected.length() == content.size) { "Backup file count mismatch" }
            val keys = expected.keys()
            while (keys.hasNext()) {
                val path = keys.next()
                val bytes = content[path] ?: throw IllegalArgumentException("Missing backup file: $path")
                val record = expected.getJSONObject(path)
                require(record.optLong("size", -1L) == bytes.size.toLong()) { "Size mismatch: $path" }
                require(record.optString("sha256") == sha256(bytes)) { "Checksum mismatch: $path" }
            }
            Verification(true, content)
        } catch (e: Exception) {
            Verification(false, emptyMap(), e.message ?: "Backup verification failed")
        }
    }

    private fun java.util.zip.ZipInputStream.readBytesBounded(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(out.size().toLong() + count <= limit.toLong()) { "Archive entry exceeds size limit" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun prune(directory: File) {
        val archives = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() } ?: return
        archives.drop(MAX_BACKUPS).forEach { it.delete() }
    }
}
