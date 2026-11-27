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

    /**
     * Full search across every source she owns.
     *
     * The old entry point capped results at five and searched one fixed mix of layers, so a
     * question spanning months of conversation could not be answered at all. This one takes an
     * explicit scope, allows far more results, and reports where each hit came from.
     *
     * @param scope one of [SCOPE_ALL], [SCOPE_MESSAGES], [SCOPE_FACTS], [SCOPE_DIARY],
     *        [SCOPE_NOTES]; unknown values fall back to all
     */
    fun search(root: File, query: String, scope: String = SCOPE_ALL, limit: Int = 12): String {
        val bounded = limit.coerceIn(1, SCOPE_MAX_RESULTS)
        val wanted = scope.lowercase().ifBlank { SCOPE_ALL }
        // An explicit search is thorough on purpose: it pages through the whole journal instead
        // of reading a tail window, because reaching things that fell out of the prompt is the
        // entire point of the tool.
        val hits = collect(root, query, wanted, fullScan = true)
        if (hits.isEmpty()) {
            return "По запросу «$query» ничего не найдено в области: $wanted."
        }
        val sorted = hits.sortedByDescending { it.second }.take(bounded)
        val builder = StringBuilder()
        builder.append("НАЙДЕНО ${sorted.size} из ${hits.size} совпадений (область: $wanted).\n")
        for ((memory, _) in sorted) {
            val when_ = if (memory.timestamp > 0L) {
                java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale("ru"))
                    .format(java.util.Date(memory.timestamp))
            } else "дата неизвестна"
            builder.append("- [${memory.kind}] $when_: ${memory.text.take(320)}\n")
        }
        if (hits.size > sorted.size) {
            builder.append("(показаны сильнейшие совпадения; уточни запрос или увеличь limit)\n")
        }
        return builder.toString()
    }

    /** Raw scored hits before trimming, so search and recall share one ranking. */
    private fun collect(
        root: File,
        query: String,
        scope: String,
        fullScan: Boolean = false
    ): List<Pair<Memory, Double>> {
        val queryEmotions = EmotionEngine.queryEmotions(root, query)
        val terms = tokenize(query)
        if (terms.isEmpty()) return emptyList()
        val memories = ArrayList<Memory>()
        val deletedIds = Tombstone.ids(root)
        val wantMessages = scope == SCOPE_ALL || scope == SCOPE_MESSAGES
        val wantFacts = scope == SCOPE_ALL || scope == SCOPE_FACTS
        val wantDiary = scope == SCOPE_ALL || scope == SCOPE_DIARY
        val wantNotes = scope == SCOPE_ALL || scope == SCOPE_NOTES

        // Deliberately NOT limited to recent lines: the whole point of the tool is to reach
        // things that fell out of the prompt, so it scans the journal from the start.
        if (wantMessages || wantNotes) {
            // A full scan streams the journal in bounded batches, so a large file neither loads
            // a 32 MB tail into memory at once nor silently ignores everything older than it.
            val events: List<JSONObject> = if (fullScan) {
                val collected = ArrayList<JSONObject>()
                var offset = 0L
                var guard = 0
                while (guard < MAX_SCAN_BATCHES) {
                    val batch = runCatching {
                        FileManager.readL0Batch(root, offset, maxRecords = 4000, maxBytes = 4 * 1024 * 1024)
                    }.getOrNull() ?: break
                    collected.addAll(batch.events)
                    if (!batch.hasMore || batch.nextOffset <= offset) break
                    offset = batch.nextOffset
                    guard++
                }
                collected
            } else {
                FileManager.readLastLines(
                    root, "memory/L0_raw.jsonl", maxLines = 4000, maxBytes = 8 * 1024 * 1024
                ).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            }
            for (event in events) {
                val type = event.optString("type")
                val id = event.optString("id")
                if (id.isNotBlank() && id in deletedIds) continue
                when (type) {
                    "message" -> {
                        if (!wantMessages) continue
                        val text = event.optString("text").trim()
                        if (text.isBlank()) continue
                        memories.add(
                            Memory(
                                if (event.optString("who") == "ruri") "прошлый ответ Рури" else "прошлая реплика Командира",
                                text,
                                event.optLong("timestamp", event.optLong("ts", 0L)),
                                emotionAfter = event.optJSONObject("emotion_after")
                                    ?.let { JSONObject(it.toString()) }
                            )
                        )
                    }
                    "phone_notification" -> {
                        if (!wantNotes) continue
                        val text = listOf(
                            event.optString("package"), event.optString("title"), event.optString("text")
                        ).filter { it.isNotBlank() }.joinToString(" — ")
                        if (text.isBlank()) continue
                        memories.add(
                            Memory("уведомление телефона", text,
                                event.optLong("timestamp", event.optLong("ts", 0L)))
                        )
                    }
                }
            }
        }

        if (wantFacts) {
            val facts = readJson(root, "memory/L1_facts.json").optJSONArray("facts") ?: JSONArray()
            for (i in 0 until facts.length()) {
                val fact = facts.optJSONObject(i) ?: continue
                // Rejected claims are not evidence: searching must never resurrect them silently.
                if (fact.optString("status") == "rejected") continue
                val text = fact.optString("text").trim()
                if (text.isBlank()) continue
                val label = if (fact.optString("status") == "assumption") "предположение" else "устойчивый факт"
                memories.add(
                    Memory(label, text,
                        fact.optLong("updated_at", fact.optLong("created_at", 0L)),
                        fact.optDouble("confidence", 0.6))
                )
            }
        }

        // Her own description of herself: character and appearance are part of memory too.
        // They were stored but never searchable, so "find every mention of your appearance"
        // could never be answered and she would loop trying.
        if (scope == SCOPE_ALL || scope == SCOPE_SELF) {
            val character = readJson(root, "core/character.json")
            val appearance = readJson(root, "core/appearance.json")
            val keys = character.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = character.optString(key).trim()
                if (value.length < 3) continue
                memories.add(
                    Memory("черта характера: $key", value,
                        character.optLong("updated_at", 0L))
                )
            }
            val appearanceKeys = appearance.keys()
            while (appearanceKeys.hasNext()) {
                val key = appearanceKeys.next()
                when (val value = appearance.opt(key)) {
                    is String -> if (value.trim().length >= 3) {
                        memories.add(
                            Memory("внешность: $key", value.trim(),
                                appearance.optLong("updated_at", 0L))
                        )
                    }
                    is JSONObject -> {
                        val sub = value.keys()
                        while (sub.hasNext()) {
                            val subKey = sub.next()
                            val text = value.optString(subKey).trim()
                            if (text.length >= 3) {
                                memories.add(
                                    Memory("внешность: $key/$subKey", text,
                                        appearance.optLong("updated_at", 0L))
                                )
                            }
                        }
                    }
                }
            }
            // Her self-model says what she knows about her own state and limits.
            val selfModel = readJson(root, "memory/self_model.json")
            val selfKeys = selfModel.keys()
            while (selfKeys.hasNext()) {
                val key = selfKeys.next()
                if (key !in setOf("current_focus", "identity", "project")) continue
                val value = selfModel.optString(key).trim()
                if (value.length < 3) continue
                memories.add(
                    Memory("самоописание: $key", value,
                        selfModel.optLong("updated_at", 0L))
                )
            }
        }

        if (wantDiary) {
            val diary = readJson(root, "memory/L2_diary.json").optJSONArray("entries") ?: JSONArray()
            for (i in 0 until diary.length()) {
                val entry = diary.optJSONObject(i) ?: continue
                val id = entry.optString("id")
                if (id.isNotBlank() && id in deletedIds) continue
                if (entry.optString("status") == "rejected") continue
                val text = entry.optString("text").trim()
                if (text.isBlank()) continue
                memories.add(
                    Memory("эпизод дневника", text, entry.optLong("ts", 0L),
                        emotionAfter = entry.optJSONObject("emotion_after")
                            ?.let { JSONObject(it.toString()) })
                )
            }
        }

        val now = System.currentTimeMillis()
        val tokenized = memories.map { it to tokenize(it.text) }
        val scored = ArrayList<Pair<Memory, Double>>()
        for ((memory, memoryTerms) in tokenized) {
            val overlap = terms.intersect(memoryTerms)
            if (overlap.isEmpty()) continue
            val coverage = overlap.size.toDouble() / terms.size.toDouble()
            val density = overlap.size.toDouble() / memoryTerms.size.coerceAtLeast(1).toDouble()
            val ageDays = if (memory.timestamp > 0L) {
                (now - memory.timestamp).coerceAtLeast(0L) / 86_400_000.0
            } else 3650.0
            val recency = 1.0 / (1.0 + ageDays / 365.0)
            var score = coverage * 3.0 + density + recency * 0.15 +
                memory.confidence.coerceIn(0.0, 1.0) * 0.1
            memory.emotionAfter?.let { episodeEmotion ->
                for (emotion in queryEmotions) {
                    if (episodeEmotion.optDouble(emotion, 0.0) > 50.0) score += 2.0
                }
            }
            scored.add(memory to score)
        }

        // Association step: the strongest direct matches pull in memories that share their
        // distinctive terms. Without it an answer like "tell me about the project" never reaches
        // the related episode that used a different word. Terms occurring in most memories are
        // ignored as weak links.
        val seeds = scored.sortedByDescending { it.second }.take(3)
        if (seeds.isEmpty()) return scored
        val directTexts = scored.map { it.first.text }.toSet()
        val expanded = seeds.flatMap { tokenize(it.first.text) }.toSet() - terms
        if (expanded.isEmpty()) return scored
        val documentFrequency = HashMap<String, Int>()
        for ((memory, memoryTerms) in tokenized) {
            memoryTerms.forEach { term ->
                documentFrequency[term] = (documentFrequency[term] ?: 0) + 1
            }
        }
        val maxLinkFrequency = maxOf(2, memories.size / 3)
        val linkTerms = expanded.filter {
            it.length >= 5 && (documentFrequency[it] ?: 0) <= maxLinkFrequency
        }.toSet()
        if (linkTerms.isEmpty()) return scored

        for ((memory, memoryTerms) in tokenized) {
            if (memory.text in directTexts) continue
            // A phone notification is text written by another app, not a memory she formed.
            // Linking it by a stray shared word pulled unrelated notifications into answers
            // about a completely different topic, so they only ever match directly.
            if (memory.kind.startsWith("уведомление")) continue
            val links = linkTerms.intersect(memoryTerms)
            if (links.isEmpty()) continue
            val ageDays = if (memory.timestamp > 0L) {
                (now - memory.timestamp).coerceAtLeast(0L) / 86_400_000.0
            } else 3650.0
            val recency = 1.0 / (1.0 + ageDays / 365.0)
            val score = 0.55 + links.size.coerceAtMost(3) * 0.18 + recency * 0.05
            // The label records the route, so a linked memory is never mistaken for a direct hit.
            val via = links.sorted().take(2).joinToString(", ")
            scored.add(
                memory.copy(kind = memory.kind + " (связано через: $via)") to score
            )
        }
        return scored
    }

    const val SCOPE_ALL = "all"
    const val SCOPE_MESSAGES = "messages"
    const val SCOPE_FACTS = "facts"
    const val SCOPE_DIARY = "diary"
    const val SCOPE_NOTES = "notes"
    const val SCOPE_SELF = "self"

    /** Generous on purpose: the Commander accepts the token cost of a deep search. */
    const val SCOPE_MAX_RESULTS = 60

    /** Bound on a full journal scan, so a huge file cannot stall a reply forever. */
    private const val MAX_SCAN_BATCHES = 200

    /**
     * Automatic recall used when building the prompt.
     *
     * It used to carry its own copy of the ranking logic, which drifted from the tool version.
     * Now it delegates, so a fix in one place fixes both — the only difference is that this
     * entry point stays silent when nothing matches.
     */
    fun recall(root: File, query: String, limit: Int = 6): String {
        val terms = tokenize(query)
        if (terms.isEmpty()) return ""
        // Prompt enrichment runs on every turn, so it reads a bounded tail rather than the whole
        // journal; the explicit search tool is the thorough path.
        val hits = collect(root, query, SCOPE_ALL, fullScan = false)
        if (hits.isEmpty()) return ""
        val builder = StringBuilder()
        for ((memory, _) in hits.sortedByDescending { it.second }.take(limit.coerceIn(1, 30))) {
            builder.append("- [${memory.kind}] ${memory.text.take(300)}\n")
        }
        return builder.toString()
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
