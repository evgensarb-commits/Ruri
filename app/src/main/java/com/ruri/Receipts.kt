package com.ruri

import org.json.JSONObject
import java.io.File

object Receipts {
    private const val PATH = "memory/receipts.json"
    private val lock = Any()

    private fun all(root: File): JSONObject =
        runCatching { JSONObject(FileManager.read(root, PATH)) }.getOrElse { JSONObject() }

    fun mark(root: File, id: String, field: String, v: Boolean) {
        if (id.isBlank()) return
        synchronized(lock) {
            val a = all(root)
            val r = a.optJSONObject(id) ?: JSONObject()
            r.put(field, v)
            r.put("updated_at", System.currentTimeMillis())
            a.put(id, r)
            FileManager.write(root, PATH, a.toString(2))
        }
    }

    fun delivered(root: File, id: String) = mark(root, id, "delivered", true)
    fun readByRuri(root: File, id: String) = mark(root, id, "read_by_ruri", true)
    fun readByCommander(root: File, id: String) = mark(root, id, "read_by_commander", true)
    fun repliedByRuri(root: File, id: String) = mark(root, id, "replied_by_ruri", true)
    fun commanderReplied(root: File, id: String) = mark(root, id, "commander_replied", true)

    fun export(root: File): String = synchronized(lock) { all(root).toString() }
}
