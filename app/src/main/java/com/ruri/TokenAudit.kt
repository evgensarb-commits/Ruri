package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Local-only token accounting. Kept outside L0 so audit rows cannot become
 * prompt context or alter memory retrieval and cognition.
 */
object TokenAudit {
    const val TYPE = "token_audit"
    const val PATH = "memory/token_audit.jsonl"
    private val lock = Any()

    fun breakdown(vararg parts: Pair<String, String>): JSONObject =
        JSONObject().also { out -> parts.forEach { (name, value) -> out.put(name + "_chars", value.length) } }

    fun breakdownLengths(vararg parts: Pair<String, Int>): JSONObject =
        JSONObject().also { out -> parts.forEach { (name, value) -> out.put(name + "_chars", value.coerceAtLeast(0)) } }

    fun toolResultChars(messages: JSONArray): Int {
        var total = 0
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            if (message.optString("role") == "user") {
                val value = message.optString("content")
                if (value.startsWith("[ИНСТРУМЕНТ")) total += value.length
            }
        }
        return total
    }

    fun record(
        root: File,
        stage: String,
        routeId: String,
        model: String,
        usage: JSONObject?,
        breakdown: JSONObject?,
        messagesCount: Int,
        hop: Int,
        attempt: Int,
        messageId: String = ""
    ) {
        runCatching {
            val prompt = usage?.optLong("prompt_tokens", -1L) ?: -1L
            val completion = usage?.optLong("completion_tokens", -1L) ?: -1L
            val total = usage?.optLong("total_tokens", -1L) ?: -1L
            val details = usage?.optJSONObject("prompt_tokens_details")
            val cached = details?.optLong("cached_tokens", -1L)
                ?: usage?.optLong("cache_read_input_tokens", -1L)
                ?: usage?.optLong("cached_input_tokens", -1L)
                ?: -1L
            val event = JSONObject()
                .put("type", TYPE)
                .put("ts", System.currentTimeMillis())
                .put("stage", stage)
                .put("route", routeId)
                .put("model", model)
                .put("prompt_tokens", prompt)
                .put("completion_tokens", completion)
                .put("total_tokens", total)
                .put("cached_tokens", cached)
                .put("usage_present", usage != null)
                .put("messages", messagesCount)
                .put("hop", hop)
                .put("attempt", attempt)
            if (breakdown != null) event.put("breakdown", JSONObject(breakdown.toString()))
            if (messageId.isNotBlank()) event.put("message_id", messageId)

            val target = File(root, PATH)
            synchronized(lock) {
                target.parentFile?.mkdirs()
                FileOutputStream(target, true).use { output ->
                    output.write((event.toString() + "\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                    output.fd.sync()
                }
            }
        }
    }

    fun aggregate(root: File, limit: Int = 600): JSONObject {
        val stages = JSONObject()
        val lines = FileManager.readLastLines(root, PATH, maxLines = limit.coerceIn(1, 5000))
        for (line in lines) {
            val event = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (event.optString("type") != TYPE) continue
            val stage = event.optString("stage").ifBlank { "unknown" }
            val row = stages.optJSONObject(stage) ?: JSONObject()
                .put("count", 0)
                .put("known_usage", 0)
                .put("prompt", 0L)
                .put("completion", 0L)
                .put("total", 0L)
                .put("cached", 0L)
                .put("messages", 0L)
                .put("hops", 0L)
                .put("breakdown", JSONObject())
            row.put("count", row.optInt("count") + 1)
            row.put("messages", row.optLong("messages") + event.optLong("messages", 0L).coerceAtLeast(0L))
            row.put("hops", row.optLong("hops") + event.optLong("hop", 0L).coerceAtLeast(0L))
            val prompt = event.optLong("prompt_tokens", -1L)
            val completion = event.optLong("completion_tokens", -1L)
            val total = event.optLong("total_tokens", -1L)
            val cached = event.optLong("cached_tokens", -1L)
            if (prompt >= 0L || completion >= 0L || total >= 0L) {
                row.put("known_usage", row.optInt("known_usage") + 1)
            }
            if (prompt >= 0L) row.put("prompt", row.optLong("prompt") + prompt)
            if (completion >= 0L) row.put("completion", row.optLong("completion") + completion)
            if (total >= 0L) row.put("total", row.optLong("total") + total)
            if (cached >= 0L) row.put("cached", row.optLong("cached") + cached)
            val sourceBreakdown = event.optJSONObject("breakdown")
            val sums = row.optJSONObject("breakdown") ?: JSONObject()
            if (sourceBreakdown != null) {
                val keys = sourceBreakdown.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    sums.put(key, sums.optLong(key, 0L) + sourceBreakdown.optLong(key, 0L).coerceAtLeast(0L))
                }
            }
            row.put("breakdown", sums)
            stages.put(stage, row)
        }
        return JSONObject()
            .put("type", TYPE)
            .put("events", lines.size)
            .put("stages", stages)
    }

    fun export(root: File, limit: Int = 600): String {
        val rows = FileManager.readLastLines(root, PATH, maxLines = limit.coerceIn(1, 5000))
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { it.optString("type") == TYPE }
        return JSONArray(rows).toString(2)
    }
}
