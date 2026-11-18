package com.ruri

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Consolidates L2 into a durable L3 autobiographical summary.
 * Previous versions are retained; local extractive mode is used without a provider.
 */
object MemoryConsolidator {
    private const val DIARY_PATH = "memory/L2_diary.json"
    private const val SUMMARY_PATH = "memory/L3_summary.json"
    private const val FACTS_PATH = "memory/L1_facts.json"
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val MIN_NEW_ENTRIES = 5
    private const val MAX_ENTRIES_PER_CONSOLIDATION = 40
    private val processLock = Any()

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
        val sourceEntries = batch.entries

        val factsFile = readJsonStrict(root, FACTS_PATH) ?: return false
        val facts = factsFile.optJSONArray("facts") ?: run {
            preserveInvalidShape(root, FACTS_PATH)
            return false
        }
        val sourceTimestamps = JSONArray()
        sourceEntries.forEach { sourceTimestamps.put(it.optLong("ts", 0L)) }

        val safeDiary = JSONArray()
        sourceEntries.forEach { entry ->
            val rawText = entry.optString("text")
            val safeText = SecretRedactor.redactText(rawText).take(1000)
            safeDiary.put(
                JSONObject()
                    .put("sequence", entry.optLong("sequence", 0L))
                    .put("timestamp", entry.optLong("ts", 0L))
                    .put("kind", entry.optString("kind", "thought"))
                    .put("trigger", entry.optString("trigger"))
                    .put("text", safeText)
            )
        }

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

        val prompt = SecretRedactor.redactText(
            buildPrompt(current.optString("summary"), safeFacts, safeDiary) +
                if (batch.skippedBeforeBatch > 0L) {
                    "\n\nВажно для точности автобиографии: до первой доступной записи этой пачки в rolling-окне дневника отсутствует ${batch.skippedBeforeBatch} более ранних записей. Не выдумывай их содержание; явно сохрани факт этого пробела."
                } else ""
        )
        val modelResult = if (BatteryLevel.get(context) > 12) requestBestModel(root, prompt) else null
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
            summaryText = extractiveSummary(current.optString("summary"), sourceEntries)
            sections = JSONObject().put(
                "note",
                "Локальная выборочная сводка. Семантическая консолидация будет выполнена после появления доступной модели."
            )
            method = "extractive_local"
            modelName = ""
        }
        if (batch.skippedBeforeBatch > 0L) {
            sections.put("unavailable_earlier_diary_entries", batch.skippedBeforeBatch)
            sections.put(
                "history_gap_note",
                "Часть более ранних записей уже вышла из rolling-окна L2 до этой консолидации; их содержимое не восстанавливалось и не выдумывалось."
            )
        }

        val versions = current.optJSONArray("previous_versions") ?: JSONArray()
        val oldSummary = current.optString("summary")
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

    private fun buildPrompt(previousSummary: String, facts: JSONArray, diary: JSONArray): String {
        val previous = previousSummary.take(6000)
        return """
            Обнови долговременную автобиографическую сводку Рури на основе проверенных фактов L1 и записей дневника L2.
            Это память одной продолжающейся личности, а не художественный рассказ.
            Сохраняй только важное для будущего контекста: устойчивые предпочтения, проекты, значимые события,
            изменения характера и отношений, открытые вопросы и незавершённые темы.
            Не выдумывай события, эмоции, договорённости или выводы. Отделяй факт от интерпретации.
            Если сведения противоречат прежней сводке, отмечай неопределённость вместо выдуманного разрешения.
            Личный контекст (предпочтения, проекты, отношения) сохраняй, если он важен для будущей памяти.
            Никогда не включай в сводку пароли, коды подтверждения и доступа, токены, ключи или номера карт.
            Верни только JSON:
            {"summary":"связный компактный текст","sections":{"commander_context":[],"ongoing_projects":[],"stable_preferences":[],"important_events":[],"relationship_context":"","open_questions":[],"uncertainties":[]}}
            Предыдущая сводка:
            $previous
            Проверенные факты L1:
            $facts
            Записи L2:
            $diary
        """.trimIndent()
    }

    private fun requestBestModel(root: java.io.File, prompt: String): Pair<JSONObject, String>? {
        val config = try { JSONObject(SecretStore.readRuntimeConfig(root)) } catch (_: Exception) { return null }
        val models = config.optJSONArray("models") ?: return null
        if (models.length() == 0) return null
        val current = config.optInt("current", 0)
        val order = FreeModelRouter.orderedIndices(models, current, requireNsfw = false)
        for (index in order) {
            val model = models.optJSONObject(index) ?: continue
            val key = model.optString("api_key")
            if (key.isBlank() && !model.optBoolean("allow_anonymous", false)) continue
            val response = request(model, SecretRedactor.redactText(prompt)) ?: continue
            val parsed = parseJson(response) ?: continue
            if (parsed.optString("summary").isNotBlank()) {
                val name = model.optString("name", model.optString("id", "model"))
                return parsed to name
            }
        }
        return null
    }

    private fun request(model: JSONObject, prompt: String): String? {
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
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return null
            if (status !in 200..299) return null
            JSONObject(raw).optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
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

    internal fun extractiveSummary(previousSummary: String, entries: List<JSONObject>): String {
        val unique = LinkedHashSet<String>()
        entries.asReversed().forEach { entry ->
            val value = entry.optString("text").replace(Regex("\\s+"), " ").trim()
            if (value.length >= 20) unique.add(value.take(500))
        }
        // Preserve every record in the current 40-entry page when the LLM is unavailable.
        // The compact offline summary favours the latest pages while L1/L0 keep their own source history.
        val recent = unique.toList().asReversed().joinToString("\n").take(16000)
        val previous = previousSummary.trim().takeLast(6000)
        return buildString {
            if (previous.isNotBlank()) append("Ранее сохранённая автобиографическая сводка:\n").append(previous)
            if (recent.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Новые записи:\n").append(recent)
            }
        }.takeLast(22000).ifBlank { "Содержательных записей для сводки пока недостаточно." }
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
