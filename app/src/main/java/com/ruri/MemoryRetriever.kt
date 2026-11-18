package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local associative recall over autobiographical events, diary entries and durable facts.
 * This first version uses transparent lexical matching and can later be replaced by embeddings
 * without changing the memory file formats.
 */
object MemoryRetriever {
    private val stopWords = setOf(
        "это", "как", "что", "когда", "где", "если", "или", "для", "при", "про",
        "мне", "меня", "мной", "мое", "моя", "мой", "мои", "тебе", "тебя", "тобой",
        "его", "ее", "они", "она", "оно", "мы", "вы", "вам", "вас", "был", "была",
        "были", "будет", "есть", "нет", "так", "там", "тут", "уже", "ещё", "еще",
        "очень", "просто", "только", "можно", "нужно", "хочу", "хотел", "сейчас"
    )

    private data class Memory(
        val kind: String,
        val text: String,
        val timestamp: Long,
        val confidence: Double = 1.0
    )

    fun recall(root: File, query: String, limit: Int = 6): String {
        val terms = tokenize(query)
        if (terms.isEmpty()) return ""
        val memories = ArrayList<Memory>()

        val facts = readJson(root, "memory/L1_facts.json").optJSONArray("facts") ?: JSONArray()
        for (i in 0 until facts.length()) {
            val fact = facts.optJSONObject(i) ?: continue
            val text = fact.optString("text").trim()
            if (text.isNotBlank()) memories.add(
                Memory("устойчивый факт", text, fact.optLong("updated_at", fact.optLong("created_at", 0L)),
                    fact.optDouble("confidence", 1.0))
            )
        }

        val diary = readJson(root, "memory/L2_diary.json").optJSONArray("entries") ?: JSONArray()
        for (i in 0 until diary.length()) {
            val entry = diary.optJSONObject(i) ?: continue
            val text = entry.optString("text").trim()
            if (text.isNotBlank()) memories.add(
                Memory("эпизод дневника", text, entry.optLong("ts", 0L))
            )
        }

        val events = FileManager.readLastLines(
            root, "memory/L0_raw.jsonl", maxLines = 20000, maxBytes = 4 * 1024 * 1024
        )
        val messageEvents = events.mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }.filter { it.optString("type") == "message" }
        // Recent conversation is already supplied as chat history; recall older episodes here.
        for (event in messageEvents.dropLast(14)) {
            val text = event.optString("text").trim()
            if (text.isNotBlank()) memories.add(
                Memory(
                    if (event.optString("who") == "ruri") "прошлый ответ Рури" else "прошлая реплика Командира",
                    text, event.optLong("timestamp", event.optLong("ts", 0L))
                )
            )
        }

        val now = System.currentTimeMillis()
        return memories.mapNotNull { memory ->
            val memoryTerms = tokenize(memory.text)
            val overlap = terms.count { it in memoryTerms }
            if (overlap == 0) return@mapNotNull null
            val coverage = overlap.toDouble() / terms.size.toDouble()
            val density = overlap.toDouble() / memoryTerms.size.coerceAtLeast(1).toDouble()
            val ageDays = if (memory.timestamp > 0L) ((now - memory.timestamp).coerceAtLeast(0L) / 86400000.0) else 3650.0
            val recency = 1.0 / (1.0 + ageDays / 365.0)
            val score = coverage * 3.0 + density + recency * 0.15 + memory.confidence.coerceIn(0.0, 1.0) * 0.1
            memory to score
        }.sortedByDescending { it.second }
            .take(limit.coerceIn(1, 12))
            .joinToString("\n") { (memory, score) ->
                val date = if (memory.timestamp > 0L) java.text.SimpleDateFormat(
                    "yyyy-MM-dd", java.util.Locale.ROOT
                ).format(java.util.Date(memory.timestamp)) else "дата неизвестна"
                "- [${memory.kind}; ${date}; совпадение ${"%.2f".format(java.util.Locale.ROOT, score)}] ${memory.text.take(650)}"
            }
    }

    private fun tokenize(value: String): Set<String> =
        Regex("[\\p{L}\\p{N}]{3,}").findAll(value.lowercase())
            .map { it.value }
            .filter { it !in stopWords }
            .toSet()

    private fun readJson(root: File, path: String): JSONObject {
        val raw = FileManager.read(root, path)
        return if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }
}
