package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Tombstone {
    private const val PATH = "memory/tombstones.json"
    private val lock = Any()

    fun ids(root: File): Set<String> = synchronized(lock) {
        runCatching {
            val a = JSONObject(FileManager.read(root, PATH)).optJSONArray("ids") ?: JSONArray()
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()
        }.getOrElse { emptySet() }
    }

    fun add(root: File, id: String) {
        if (id.isBlank()) return
        synchronized(lock) {
            val j = runCatching { JSONObject(FileManager.read(root, PATH)) }.getOrElse { JSONObject() }
            val a = j.optJSONArray("ids") ?: JSONArray().also { j.put("ids", it) }
            if ((0 until a.length()).any { a.optString(it) == id }) return
            a.put(id)
            FileManager.write(root, PATH, j.toString(2))
            FileManager.appendEvent(root, JSONObject()
                .put("type", "message_deleted")
                .put("target_id", id)
                .put("ts", System.currentTimeMillis()))
        }
    }

    fun isDeleted(root: File, id: String): Boolean = id.isNotBlank() && id in ids(root)
}
