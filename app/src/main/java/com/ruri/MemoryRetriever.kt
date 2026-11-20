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
        val confidence: Double = 1.0,
        val emotionAfter: JSONObject? = null
    )

    fun recall(root: File, query: String, limit: Int = 6): String {
        val queryEmotions = EmotionEngine.queryEmotions(root, query)
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
                Memory(
                    "эпизод дневника", text, entry.optLong("ts", 0L),
                    emotionAfter = entry.optJSONObject("emotion_after")?.let { JSONObject(it.toString()) }
                )
            )
        }

        val events = FileManager.readLastLines(
            root, "memory/L0_raw.jsonl", maxLines = 20000, maxBytes = 4 * 1024 * 1024
        )
        val parsedEvents = events.mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }
        val messageEvents = parsedEvents.filter { it.optString("type") == "message" }
        // Recent conversation is already supplied as chat history; recall older episodes here.
        for (event in messageEvents.dropLast(14)) {
            val text = event.optString("text").trim()
            if (text.isNotBlank()) memories.add(
                Memory(
                    if (event.optString("who") == "ruri") "прошлый ответ Рури" else "прошлая реплика Командира",
                    text, event.optLong("timestamp", event.optLong("ts", 0L)),
                    emotionAfter = event.optJSONObject("emotion_after")?.let { JSONObject(it.toString()) }
                )
            )
        }

        // Older notification bodies remain searchable in local L0, not just the last
        // few notifications copied into a live model prompt.
        for (event in parsedEvents.filter { it.optString("type") == "phone_notification" }) {
            val packageName = event.optString("package")
            val title = event.optString("title").trim()
            val body = event.optString("text").trim()
            val text = listOf(packageName, title, body).filter { it.isNotBlank() }.joinToString(" — ")
            if (text.isNotBlank() && terms.intersect(tokenize(text)).isNotEmpty()) {
                memories.add(
                    Memory(
                        "уведомление телефона",
                        text,
                        event.optLong("timestamp", event.optLong("ts", 0L))
                    )
                )
            }
        }

        val now = System.currentTimeMillis()
        val tokenizedMemories = memories.map { memory -> memory to tokenize(memory.text) }
        val direct = tokenizedMemories.mapNotNull { (memory, memoryTerms) ->
            val overlapTerms = terms.intersect(memoryTerms)
            if (overlapTerms.isEmpty()) return@mapNotNull null
            val coverage = overlapTerms.size.toDouble() / terms.size.toDouble()
            val density = overlapTerms.size.toDouble() / memoryTerms.size.coerceAtLeast(1).toDouble()
            val ageDays = if (memory.timestamp > 0L) ((now - memory.timestamp).coerceAtLeast(0L) / 86400000.0) else 3650.0
            val recency = 1.0 / (1.0 + ageDays / 365.0)
            var score = coverage * 3.0 + density + recency * 0.15 +
                memory.confidence.coerceIn(0.0, 1.0) * 0.1
            memory.emotionAfter?.let { episodeEmotion ->
                for (emotion in queryEmotions) if (episodeEmotion.optDouble(emotion, 0.0) > 50.0) score += 2.0
            }
            Triple(memory, score, overlapTerms)
        }.sortedByDescending { it.second }

        // Build a small, transient association network from the strongest direct matches.
        // It is derived from source memories on each recall, so it cannot become stale.
        val seeds = direct.take(3)
        val expandedTerms = seeds.flatMap { seed ->
            tokenize(seed.first.text)
        }.toSet() - terms
        val documentFrequency = HashMap<String, Int>()
        tokenizedMemories.forEach { (_, memoryTerms) ->
            memoryTerms.forEach { term -> documentFrequency[term] = (documentFrequency[term] ?: 0) + 1 }
        }
        // Common words are weak links; retain terms that occur in only a minority of memories.
        val maxLinkFrequency = maxOf(2, memories.size / 3)
        val associationTerms = expandedTerms.filter {
            it.length >= 5 && (documentFrequency[it] ?: 0) <= maxLinkFrequency
        }.toSet()
        val directMemories = direct.map { it.first }.toSet()
        val associated = if (associationTerms.isEmpty()) emptyList() else tokenizedMemories.mapNotNull { (memory, memoryTerms) ->
            if (memory in directMemories) return@mapNotNull null
            val links = associationTerms.intersect(memoryTerms)
            if (links.isEmpty()) return@mapNotNull null
            val ageDays = if (memory.timestamp > 0L) ((now - memory.timestamp).coerceAtLeast(0L) / 86400000.0) else 3650.0
            val recency = 1.0 / (1.0 + ageDays / 365.0)
            var score = 0.55 + links.size.coerceAtMost(3) * 0.18 + recency * 0.05
            memory.emotionAfter?.let { episodeEmotion ->
                for (emotion in queryEmotions) if (episodeEmotion.optDouble(emotion, 0.0) > 50.0) score += 2.0
            }
            Triple(memory, score, links)
        }.sortedByDescending { it.second }

        // Reserve room for associative hops; otherwise many direct hits can hide every link.
        val selected = (direct.take(3).map { Triple(it.first, it.second, "прямое совпадение") } +
            associated.map { Triple(it.first, it.second, "связано через: " + it.third.sorted().take(4).joinToString(", ")) })
            .take(limit.coerceIn(1, 12))
        return selected.joinToString("\n") { (memory, score, relation) ->
            val date = if (memory.timestamp > 0L) java.text.SimpleDateFormat(
                "yyyy-MM-dd", java.util.Locale.ROOT
            ).format(java.util.Date(memory.timestamp)) else "дата неизвестна"
            "- [${memory.kind}; ${date}; ${relation}; оценка ${"%.2f".format(java.util.Locale.ROOT, score)}] ${memory.text}"
        }
    }

    private fun tokenize(value: String): Set<String> =
        Regex("[\\p{L}\\p{N}]{3,}").findAll(value.lowercase())
            .map { normalizeToken(it.value) }
            .filter { it.length >= 3 && it !in stopWords }
            .toSet()

    // Lightweight suffix normalization improves Russian case/number matching without
    // requiring a network service or a large morphology library.
    private fun normalizeToken(token: String): String {
        val suffixes = listOf(
            "иями", "ями", "ами", "ого", "ему", "ому", "ыми", "ими",
            "ая", "яя", "ое", "ее", "ые", "ие", "ый", "ий", "ой",
            "ую", "юю", "ам", "ям", "ах", "ях", "ов", "ев", "ей",
            "ом", "ем", "а", "я", "ы", "и", "у", "ю", "е", "о"
        )
        for (suffix in suffixes) {
            if (token.length - suffix.length >= 3 && token.endsWith(suffix)) {
                return token.dropLast(suffix.length)
            }
        }
        return token
    }

    private fun readJson(root: File, path: String): JSONObject {
        val raw = FileManager.read(root, path)
        return if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }
}
