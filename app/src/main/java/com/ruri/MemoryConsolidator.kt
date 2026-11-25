package com.ruri

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Builds L3, her autobiography, out of what she actually knows to be true.
 *
 * L3 is a **rewritten synthesis** of verified facts (L1), her self-model and her character —
 * never a log of diary thoughts. Diary entries (L2) stay inside L2: they are momentary moods
 * and would otherwise turn the autobiography into a pile of stale laments that nothing
 * removes. Previous versions are kept for safety.
 */
object MemoryConsolidator {
    private const val DIARY_PATH = "memory/L2_diary.json"
    private const val SUMMARY_PATH = "memory/L3_summary.json"
    private const val FACTS_PATH = "memory/L1_facts.json"
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val MIN_NEW_ENTRIES = 5
    private const val MAX_ENTRIES_PER_CONSOLIDATION = 40
    private val processLock = Any()

    internal fun thoughtEntries(entries: List<JSONObject>): List<JSONObject> =
        entries.filter { it.optString("kind", "thought") == "thought" }

    internal data class SequenceBatch(
        val entries: List<JSONObject>,
        val firstSequence: Long,
        val lastSequence: Long,
        val skippedBeforeBatch: Long,
        val totalEntries: Long
    )

    internal fun shouldConsolidateBatch(
        batch: SequenceBatch,
        previousCount: Long,
        lastCompressedAt: Long,
        now: Long
    ): Boolean {
        if ((batch.totalEntries - previousCount).coerceAtLeast(0L) < MIN_NEW_ENTRIES) return false
        if (batch.entries.isEmpty()) return false
        // A short batch is acceptable only when older uncompressed history has already
        // fallen out of L2's rolling window and the skipped range will be recorded in L3.
        if (batch.entries.size < MIN_NEW_ENTRIES && batch.skippedBeforeBatch == 0L) return false
        if (lastCompressedAt > 0L && now - lastCompressedAt < DAY_MS) return false
        return true
    }

    /**
     * Selects the oldest uncompressed diary records. L2 is a rolling window,
     * so the L3 cursor advances only to the last record actually included.
     */
    internal fun selectEntriesForConsolidation(
        diary: JSONObject,
        previousCount: Long,
        limit: Int = 40
    ): SequenceBatch {
        require(limit > 0)
        val entries = diary.optJSONArray("entries") ?: JSONArray()
        var total = maxOf(
            diary.optLong("total_entries", entries.length().toLong()),
            entries.length().toLong()
        )
        for (index in 0 until entries.length()) {
            val sequence = entries.optJSONObject(index)?.optLong("sequence", 0L) ?: 0L
            if (sequence > total) total = sequence
        }
        val legacyBase = (total - entries.length()).coerceAtLeast(0L)
        val sequenced = ArrayList<Pair<Long, JSONObject>>()
        for (index in 0 until entries.length()) {
            val original = entries.optJSONObject(index) ?: continue
            val storedSequence = original.optLong("sequence", 0L)
            val sequence = if (storedSequence > 0L) storedSequence else legacyBase + index + 1L
            if (sequence <= previousCount) continue
            val copy = JSONObject(original.toString()).put("sequence", sequence)
            sequenced.add(sequence to copy)
        }
        sequenced.sortBy { it.first }
        val selected = sequenced.take(limit)
        val first = selected.firstOrNull()?.first ?: 0L
        val last = selected.lastOrNull()?.first ?: previousCount
        val skipped = if (first > 0L) (first - previousCount - 1L).coerceAtLeast(0L) else 0L
        return SequenceBatch(selected.map { it.second }, first, last, skipped, total)
    }

    fun consolidateIfDue(context: Context): Boolean = synchronized(processLock) {
        consolidateLocked(context)
    }

    private fun consolidateLocked(context: Context): Boolean {
        val root = context.filesDir
        val diary = readJsonStrict(root, DIARY_PATH) ?: return false
        val entries = diary.optJSONArray("entries") ?: run {
            preserveInvalidShape(root, DIARY_PATH)
            return false
        }
        val current = readJsonStrict(root, SUMMARY_PATH) ?: return false
        if (!current.has("summary")) {
            preserveInvalidShape(root, SUMMARY_PATH)
            return false
        }
        // L2 is a rolling window: consume the oldest uncompressed records in bounded pages.
        val previousCount = current.optLong("last_compressed_entry_count", 0L)
        val batch = selectEntriesForConsolidation(diary, previousCount, MAX_ENTRIES_PER_CONSOLIDATION)
        val previousCompressedAt = current.optLong("last_compressed", 0L)
        val totalEntries = batch.totalEntries
        val now = System.currentTimeMillis()
        if (!shouldConsolidateBatch(batch, previousCount, previousCompressedAt, now)) return false
        val sourceEntries = thoughtEntries(batch.entries)
        if (sourceEntries.isEmpty()) {
            // A page containing only spoken mirrors is deliberately not autobiographical input.
            // Still advance the L3 cursor so legacy said-only pages cannot block later thoughts.
            val advanced = JSONObject(current.toString())
                .put("last_compressed_entry_count", batch.lastSequence)
                .put("total_diary_entries", totalEntries)
                .put("last_said_page_skipped_at", now)
                .put("last_said_page_skipped_count", batch.entries.size)
            FileManager.write(root, SUMMARY_PATH, advanced.toString(2))
            FileManager.appendEvent(root, JSONObject()
                .put("type", "memory_consolidated")
                .put("source", "memory")
                .put("method", "said_entries_skipped")
                .put("source_entry_count", 0)
                .put("source_sequence_from", batch.firstSequence)
                .put("source_sequence_to", batch.lastSequence)
                .put("privacy", "local"))
            return true
        }

        val factsFile = readJsonStrict(root, FACTS_PATH) ?: return false
        val facts = factsFile.optJSONArray("facts") ?: run {
            preserveInvalidShape(root, FACTS_PATH)
            return false
        }
        val sourceTimestamps = JSONArray()
        sourceEntries.forEach { sourceTimestamps.put(it.optLong("ts", 0L)) }

        val safeFacts = JSONArray()
        for (index in 0 until minOf(facts.length(), 80)) {
            val fact = facts.optJSONObject(index) ?: continue
            val text = SecretRedactor.redactText(fact.optString("text"))
            if (text.isBlank() || text.contains(SecretRedactor.PLACEHOLDER)) continue
            safeFacts.put(
                JSONObject()
                    .put("id", fact.optString("id"))
                    .put("text", text.take(400))
                    .put("category", fact.optString("category"))
                    .put("confidence", fact.optDouble("confidence", 0.0))
                    .put("source_event_ids", fact.optJSONArray("source_event_ids") ?: JSONArray())
            )
        }

        val selfModel = readJsonStrict(root, "memory/self_model.json") ?: JSONObject()
        val character = readJsonStrict(root, "core/character.json") ?: JSONObject()
        // Heal an L3 that an earlier build polluted with verbatim diary thoughts.
        val healedPrevious = stripDiaryEchoes(current.optString("summary"), entries)
        val prompt = SecretRedactor.redactText(
            buildPrompt(healedPrevious, safeFacts, selfModel, character) +
                if (batch.skippedBeforeBatch > 0L) {
                    "\n\nВажно для точности автобиографии: до первой доступной записи этой пачки в rolling-окне дневника отсутствует ${batch.skippedBeforeBatch} более ранних записей. Не выдумывай их содержание; явно сохрани факт этого пробела."
                } else ""
        )
        val modelResult = requestBestModel(root, prompt)
        val summaryText: String
        val sections: JSONObject
        val method: String
        val modelName: String

        if (modelResult != null && modelResult.first.optString("summary").isNotBlank()) {
            summaryText = SecretRedactor.redactText(
                modelResult.first.optString("summary").trim()
            ).take(9000)
            sections = SecretRedactor.redactEvent(
                modelResult.first.optJSONObject("sections") ?: JSONObject()
            )
            method = "semantic_llm"
            modelName = modelResult.second
        } else {
            // No provider: build a factual summary from L1 only. Diary thoughts are never used
            // here, otherwise an offline run would pollute the autobiography again.
            summaryText = localFactSummary(selfModel, character, safeFacts)
            sections = JSONObject()
                .put("who_i_am", selfModel.optString("identity"))
                .put("note", "Локальная сводка по фактам L1. Семантическая сборка будет выполнена после появления доступной модели.")
            method = "local_facts_only"
            modelName = ""
        }
        if (batch.skippedBeforeBatch > 0L) {
            sections.put("unavailable_earlier_diary_entries", batch.skippedBeforeBatch)
            sections.put(
                "history_gap_note",
                "Часть более ранних записей уже вышла из rolling-окна L2 до этой консолидации; их содержимое не восстанавливалось и не выдумывалось."
            )
        }

        val rawVersions = current.optJSONArray("previous_versions") ?: JSONArray()
        val versions = SecretRedactor.redactEvent(
            JSONObject().put("versions", rawVersions)
        ).optJSONArray("versions") ?: JSONArray()
        val oldSummary = SecretRedactor.redactText(current.optString("summary"))
        if (oldSummary.isNotBlank()) {
            versions.put(
                JSONObject()
                    .put("saved_at", previousCompressedAt)
                    .put("entry_count", previousCount)
                    .put("summary", oldSummary)
                    .put("method", current.optString("method", "legacy"))
            )
        }
        while (versions.length() > 10) versions.remove(0)

        val factIds = JSONArray()
        for (index in 0 until safeFacts.length()) {
            val id = safeFacts.optJSONObject(index)?.optString("id") ?: ""
            if (id.isNotBlank()) factIds.put(id)
        }

        val updated = JSONObject()
            .put("schema_version", 2)
            .put("summary", summaryText)
            .put("sections", sections)
            .put("last_compressed", now)
            .put("last_compressed_entry_count", batch.lastSequence)
            .put("total_diary_entries", totalEntries)
            .put("source_sequence_from", batch.firstSequence)
            .put("source_sequence_to", batch.lastSequence)
            .put("unavailable_entries_before_batch", batch.skippedBeforeBatch)
            .put("source_entry_timestamps", sourceTimestamps)
            .put("source_fact_ids", factIds)
            .put("method", method)
            .put("model", modelName)
            .put("previous_versions", versions)
        FileManager.write(root, SUMMARY_PATH, updated.toString(2))
        FileManager.appendEvent(
            root,
            JSONObject()
                .put("type", "memory_consolidated")
                .put("source", "memory")
                .put("method", method)
                .put("model", modelName)
                .put("source_entry_count", sourceEntries.size)
                .put("source_sequence_from", batch.firstSequence)
                .put("source_sequence_to", batch.lastSequence)
                .put("unavailable_entries_before_batch", batch.skippedBeforeBatch)
                .put("fact_count", safeFacts.length())
                .put("privacy", "local")
        )
        Log.i("Ruri", "L3 memory consolidated using " + method)
        return true
    }

    private fun buildPrompt(
        previousSummary: String,
        facts: JSONArray,
        selfModel: JSONObject,
        character: JSONObject
    ): String {
        // The caller already stripped verbatim diary echoes from the previous summary.
        val previous = previousSummary.take(6000)
        return """
            Ты обновляешь автобиографию Рури — искусственной личности, которая живёт на телефоне Командира.
            Это справка о ней самой, а не дневник и не художественный текст.

            Правила:
            - Источник истины только проверенные факты L1, её самоописание и характер. Дневника здесь нет.
            - НЕ цитируй и НЕ пересказывай мысли, эмоции и жалобы. Никаких «я скучала», «куда он пропал»,
              «я почувствовала». Автобиография фиксирует, что известно, а не что пережито в моменте.
            - Это ПЕРЕЗАПИСЬ, а не дописывание: собери актуальную картину заново из фактов.
              Не сохраняй прежние формулировки, если они больше не подтверждаются фактами.
            - Не выдумывай события, договорённости и выводы. Отделяй факт от предположения.
            - Пиши в третьем лице или нейтрально («Рури — ...», «Командир ...»), компактно.
            - Никогда не включай пароли, коды, токены, ключи и номера карт.

            Верни только JSON:
            {"summary":"связная компактная автобиография","sections":{"who_i_am":"","commander_context":[],"ongoing_projects":[],"stable_preferences":[],"important_events":[],"open_questions":[]}}

            Прежняя автобиография (может содержать устаревшее — перепиши, не копируй):
            $previous
            Проверенные факты L1:
            $facts
            Самоописание:
            $selfModel
            Характер:
            $character
        """.trimIndent()
    }

    /**
     * Removes lines from a summary that are verbatim diary thoughts.
     *
     * Earlier builds appended diary text into L3, so an existing summary may be polluted. Any
     * line that fully matches a known thought is dropped, which heals the file in place without
     * touching L2 itself.
     */
    internal fun stripDiaryEchoes(summary: String, diaryEntries: JSONArray): String {
        if (summary.isBlank()) return ""
        val known = HashSet<String>()
        for (index in 0 until diaryEntries.length()) {
            val text = normalizeForCompare(diaryEntries.optJSONObject(index)?.optString("text").orEmpty())
            if (text.length >= 20) known.add(text)
        }
        val kept = ArrayList<String>()
        for (line in summary.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                kept.add(line)
                continue
            }
            val normalized = normalizeForCompare(trimmed)
            val isEcho = normalized.length >= 20 && known.contains(normalized)
            if (!isEcho) kept.add(line)
        }
        return kept.joinToString("\n").trim()
    }

    private fun normalizeForCompare(value: String): String =
        value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun requestBestModel(root: java.io.File, prompt: String): Pair<JSONObject, String>? {
        val config = try { JSONObject(SecretStore.readRuntimeConfig(root)) } catch (_: Exception) { return null }
        val models = config.optJSONArray("models") ?: return null
        if (models.length() == 0) return null
        // One route only: index 0 or nothing.
        val order: List<Int> = if (models.length() > 0) listOf(0) else emptyList()
        for (index in order) {
            val model = models.optJSONObject(index) ?: continue
            // L1/L2/L3 content is personal memory; minimal-context routes are ineligible here.
            val key = model.optString("api_key")
            if (key.isBlank()) continue
            val routeId = model.optString("id").ifBlank { model.optString("name", "route_$index") }
            runCatching { RouteMeta.recordAttempt(root, routeId) }
            val response = request(model, SecretRedactor.redactText(prompt), root, routeId)
            if (response.isNullOrBlank()) continue
            val parsed = parseJson(response)
            if (parsed == null) {
                runCatching { RouteMeta.recordFailure(root, routeId, "other", Task.REASON) }
                continue
            }
            if (parsed.optString("summary").isNotBlank()) {
                runCatching { RouteMeta.recordSuccess(root, routeId, Task.REASON) }
                val name = model.optString("name", model.optString("id", "model"))
                return parsed to name
            }
        }
        return null
    }

    private fun request(model: JSONObject, prompt: String, root: java.io.File, routeId: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val base = model.optString("base_url").trimEnd('/')
            require(base.startsWith("https://", ignoreCase = true)) { "Provider endpoint must use HTTPS" }
            connection = (URL(base + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 25000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                val key = model.optString("api_key")
                if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer " + key)
            }
            val body = JSONObject()
                .put("model", model.optString("model"))
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                .put("temperature", 0.2)
                .put("max_tokens", 1400)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            RouteMeta.recordHttpStatus(root, routeId, status)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                RouteMeta.recordFailure(root, routeId, RouteSelector.classify(status, null, raw), Task.REASON)
                return null
            }
            JSONObject(raw).optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            RouteMeta.recordFailure(root, routeId, RouteSelector.classify(null, e), Task.REASON)
            Log.w("Ruri", "L3 consolidation provider request failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseJson(raw: String): JSONObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
    }

    /**
     * Offline autobiography: facts and self-knowledge only.
     *
     * Deliberately ignores diary text — an automatic run without a provider must never be able
     * to turn L3 into a transcript of moods.
     */
    internal fun localFactSummary(
        selfModel: JSONObject,
        character: JSONObject,
        facts: JSONArray
    ): String {
        val builder = StringBuilder()
        val identity = selfModel.optString("identity").ifBlank { character.optString("name") }
        if (identity.isNotBlank()) {
            builder.append("Кто я: ").append(identity)
            val designation = character.optString("designation")
            if (designation.isNotBlank()) builder.append(" (").append(designation).append(")")
            builder.append(". Искусственная личность, живущая на телефоне Командира.").append("\n")
        }
        val capabilities = selfModel.optJSONArray("known_capabilities")
        if (capabilities != null && capabilities.length() > 0) {
            val names = (0 until capabilities.length())
                .mapNotNull { index -> capabilities.optString(index).takeIf { it.isNotBlank() } }
            if (names.isNotEmpty()) {
                builder.append("Что умею: ").append(names.joinToString(", ")).append(".").append("\n")
            }
        }
        val byCategory = LinkedHashMap<String, MutableList<String>>()
        for (index in 0 until facts.length()) {
            val fact = facts.optJSONObject(index) ?: continue
            val text = SecretRedactor.redactText(fact.optString("text"))
                .replace(Regex("\\s+"), " ").trim()
            // Keep a fact whose only problem is an embedded secret: it is already masked, and
            // dropping it would lose real information. Skip only when nothing meaningful remains.
            val meaningful = text.replace(SecretRedactor.PLACEHOLDER, "").trim()
            if (meaningful.length < 5) continue
            val category = fact.optString("category").ifBlank { "общее" }
            byCategory.getOrPut(category) { ArrayList() }.add(text.take(300))
        }
        for ((category, values) in byCategory) {
            builder.append("\n").append(category).append(":").append("\n")
            values.forEach { value -> builder.append("- ").append(value).append("\n") }
        }
        return builder.toString().trim().take(9000)
            .ifBlank { "Автобиография ещё не собрана: фактов недостаточно." }
    }

    private fun readJsonStrict(root: java.io.File, path: String): JSONObject? {
        val raw = FileManager.read(root, path)
        if (raw.isBlank()) return null
        return try { JSONObject(raw) } catch (e: Exception) {
            try {
                val name = path.replace('/', '_').replace('\\', '_')
                val backup = java.io.File(root, "backup/" + name + "_corrupt_" + System.currentTimeMillis() + ".json")
                backup.parentFile?.mkdirs()
                if (!backup.exists()) backup.writeText(raw, Charsets.UTF_8)
            } catch (_: Exception) { }
            Log.w("Ruri", "L3 source file is malformed and was preserved", e)
            null
        }
    }

    private fun preserveInvalidShape(root: java.io.File, path: String) {
        try {
            val source = java.io.File(root, path)
            if (!source.isFile) return
            val name = path.replace('/', '_').replace('\\', '_')
            val backup = java.io.File(
                root,
                "backup/" + name + "_invalid_shape_" + source.lastModified() + ".json"
            )
            backup.parentFile?.mkdirs()
            if (!backup.exists()) backup.writeText(source.readText(Charsets.UTF_8), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("Ruri", "Could not preserve invalid JSON structure for " + path, e)
        }
    }

    private fun readJson(root: java.io.File, path: String): JSONObject {
        val raw = FileManager.read(root, path)
        if (raw.isBlank()) return JSONObject()
        return try { JSONObject(raw) } catch (e: Exception) {
            Log.w("Ruri", "Cannot read " + path + " during L3 consolidation", e)
            JSONObject()
        }
    }
}
