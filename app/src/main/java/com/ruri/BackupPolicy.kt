package com.ruri

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** A small rolling safety layer alongside the verified ZIP backup system. */
object BackupPolicy {
    const val ROLLING_MS = 60L * 60L * 1000L
    const val STATE_MS = 15L * 60L * 1000L
    const val KEEP = 5
    const val META = "backup/rolling_meta.json"

    private fun meta(root: File): JSONObject = try {
        val raw = FileManager.read(root, META)
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    } catch (_: Exception) {
        JSONObject()
    }

    fun atomicWrite(root: File, relativePath: String, content: String) {
        require(relativePath.startsWith("backup/")) { "Rolling backup writes are restricted to backup/" }
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relativePath).canonicalFile
        require(target.path.startsWith(canonicalRoot.path + File.separator)) { "Invalid backup path" }
        val parent = target.parentFile ?: throw IllegalArgumentException("Backup path has no parent")
        if (!parent.exists() && !parent.mkdirs()) throw IllegalStateException("Cannot create backup directory")
        val temporary = File(parent, target.name + ".tmp-" + UUID.randomUUID())
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** Saves a separate last-known-good L4 checkpoint at most every 15 minutes. */
    fun checkpointState(root: File) {
        val now = System.currentTimeMillis()
        val metadata = meta(root)
        if (now - metadata.optLong("last_state_checkpoint", 0L) < STATE_MS) return
        val state = FileManager.read(root, "memory/L4_state.json")
        if (state.isBlank()) return
        atomicWrite(root, "backup/checkpoints/L4_state.json", state)
        metadata.put("last_state_checkpoint", now)
        atomicWrite(root, META, metadata.toString(2))
    }

    private fun charging(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent != null && intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun idle(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isDeviceIdleMode

    fun maybeRolling(context: Context, trigger: String) {
        val root = context.filesDir
        val now = System.currentTimeMillis()
        val metadata = meta(root)
        val last = metadata.optLong("last_rolling", 0L)
        val due = now - last >= ROLLING_MS ||
            trigger == "pause" ||
            trigger == "significant" ||
            (charging(context) && idle(context))
        if (!due) return

        val snapshot = JSONObject()
        for (directory in listOf("core", "memory")) {
            val names = JSONArray(FileManager.list(root, directory))
            for (index in 0 until names.length()) {
                val name = names.optString(index)
                val relative = "$directory/$name"
                if (relative == "core/secrets.enc.json") continue
                val content = FileManager.read(root, relative)
                if (content.isNotBlank()) snapshot.put(relative, content)
            }
        }
        if (snapshot.length() == 0) return
        atomicWrite(root, "backup/rolling/ruri-$now.json", snapshot.toString())
        metadata.put("last_rolling", now)
        atomicWrite(root, META, metadata.toString(2))
        prune(root)
    }

    private fun prune(root: File) {
        val directory = File(root, "backup/rolling")
        val files = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedBy { it.name } ?: return
        if (files.size > KEEP) {
            files.take(files.size - KEEP).forEach { it.delete() }
        }
    }
}
