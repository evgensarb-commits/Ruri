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
    private const val MIN_ENTRIES = 10
    private const val MIN_NEW_ENTRIES = 5

    fun consolidateIfDue(context: Context): Boolean {
        val root = context.filesDir
        val diary = readJson(root, DIARY_PATH)
        val entries = diary.optJSONArray("entries") ?: JSONArray()
        if (entries.length() < MIN_ENTRIES) return false

        val current = readJsonStrict(root, SUMMARY_PATH) ?: return false
        // L2 is a rolling window. Its array length stops growing at 500, so
        // consolidation progress must use the monotonic lifetime counter.
        val totalEntries = maxOf(diary.optLong("total_entries", entries.length().toLong()), entries.length().toLong())
        val previousCount = current.optLong("last_compressed_entry_count", 0L)
        val previousCompressedAt = current.optLong("last_compressed", 0L)
        if (totalEntries - previousCount < MIN_NEW_ENTRIES) return false
        if (previousCompressedAt > 0L && System.currentTimeMillis() - previousCompressedAt < DAY_MS) return false

        val now = System.currentTimeMillis()
        val sourceEntries = (maxOf(0, entries.length() - 40) until entries.length()).mapNotNull { index ->
            entries.optJSONObject(index)?.let { JSONObject(it.toString()) }
        }
        if (sourceEntries.isEmpty()) return false

        val facts = readJson(root, FACTS_PATH).optJSONArray("facts") ?: JSONArray()
        val sourceTimestamps = JSONArray()
        sourceEntries.forEach { sourceTimestamps.put(it.optLong("ts", 0L)) }

        val safeDiary = JSONArray()
        sourceEntries.forEach { entry ->
            val rawText = entry.optString("text")
            val safeText = CredentialGuard.redact(
                rawText,
                "[содержимое записи пропущено локальным фильтром приватности]"
            ).take(1000)
            safeDiary.put(
                JSONObject()
                    .put("timestamp", entry.optLong("ts", 0L))
                    .put("kind", entry.optString("kind", "thought"))
                    .put("trigger", entry.optString("trigger"))
                    .put("text", safeText)
            )
        }

        val safeFacts = JSONArray()
        for (index in 0 until minOf(facts.length(), 80)) {
            val fact = facts.optJSONObject(index) ?: continue
            val text = fact.optString("text")
            if (text.isBlank() || CredentialGuard.containsCredentials(text)) continue
            safeFacts.put(
                JSONObject()
                    .put("id", fact.optString("id"))
                    .put("text", text.take(400))
                    .put("category", fact.optString("category"))
                    .put("confidence", fact.optDouble("confidence", 0.0))
                    .put("source_event_ids", fact.optJSONArray("source_event_ids") ?: JSONArray())
            )
        }

        val prompt = buildPrompt(current.optString("summary"), safeFacts, safeDiary)
        val modelResult = if (BatteryLevel.get(context) > 12) requestBestModel(root, prompt) else null
        val summaryText: String
        val sections: JSONObject
        val method: String
        val modelName: String

        if (modelResult != null && modelResult.first.optString("summary").isNotBlank()) {
            summaryText = modelResult.first.optString("summary").trim().take(9000)
            sections = modelResult.first.optJSONObject("sections") ?: JSONObject()
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
            .put("last_compressed_entry_count", totalEntries)
            .put("total_diary_entries", totalEntries)
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
            Не включай пароли, API-ключи, токены и одноразовые коды аутентификации.
            Разрешённые Командиром личные сведения сохраняй, если они важны для будущего контекста.
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
        val current = config.optInt("current", 0).coerceIn(0, models.length() - 1)
        val order = (0 until models.length()).sortedBy { if (it == current) 0 else 1 }
        for (index in order) {
            val model = models.optJSONObject(index) ?: continue
            if (model.optString("api_key").isBlank()) continue
            val response = request(model, prompt) ?: continue
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
                setRequestProperty("Authorization", "Bearer " + model.optString("api_key"))
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

    private fun extractiveSummary(previousSummary: String, entries: List<JSONObject>): String {
        val unique = LinkedHashSet<String>()
        entries.asReversed().forEach { entry ->
            val value = entry.optString("text").replace(Regex("\\s+"), " ").trim()
            if (value.length >= 20 && !CredentialGuard.containsCredentials(value)) unique.add(value.take(500))
        }
        val recent = unique.take(14).asReversed().joinToString("\n").take(3500)
        val previous = previousSummary.trim().take(4500)
        return buildString {
            if (previous.isNotBlank()) append("Ранее сохранённая автобиографическая сводка:\n").append(previous)
            if (recent.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Новые записи:\n").append(recent)
            }
        }.take(8000).ifBlank { "Содержательных записей для сводки пока недостаточно." }
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

    private fun readJson(root: java.io.File, path: String): JSONObject {
        val raw = FileManager.read(root, path)
        if (raw.isBlank()) return JSONObject()
        return try { JSONObject(raw) } catch (e: Exception) {
            Log.w("Ruri", "Cannot read " + path + " during L3 consolidation", e)
            JSONObject()
        }
    }
}
