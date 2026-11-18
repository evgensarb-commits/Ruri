package com.ruri

import org.json.JSONObject
import java.io.File

/** Stores privacy-safe provider connectivity diagnostics on the device only. */
object NetworkDiagnostics {
    private const val PATH = "memory/network_diagnostics.jsonl"
    private const val MAX_RECORDS = 200

    @Synchronized
    fun record(root: File, entry: JSONObject) {
        try {
            val file = File(root, PATH)
            file.parentFile?.mkdirs()
            file.appendText(entry.toString() + "\n", Charsets.UTF_8)
            val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            if (lines.size > MAX_RECORDS) {
                file.writeText(lines.takeLast(MAX_RECORDS).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            android.util.Log.w("Ruri", "Could not save network diagnostics: ${e.javaClass.simpleName}")
        }
    }

    fun latest(root: File): String {
        return try {
            val file = File(root, PATH)
            val line = file.useLines { it.filter { value -> value.isNotBlank() }.lastOrNull() }
                ?: return JSONObject().put("available", false).put("message", "Попыток соединения пока нет").toString()
            JSONObject(line).put("available", true).toString(2)
        } catch (_: Exception) {
            JSONObject().put("available", false).put("message", "Диагностика пока недоступна").toString()
        }
    }
}
