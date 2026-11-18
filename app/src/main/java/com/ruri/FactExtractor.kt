package com.ruri

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Extracts durable, low-sensitivity facts from several Commander messages.
 * A saved fact must cite real Commander-authored L0 event IDs.
 */
object FactExtractor {
    private const val STATE_PATH = "memory/L4_state.json"
    private const val FACTS_PATH = "memory/L1_facts.json"
    private const val L0_PATH = "memory/L0_raw.jsonl"
    private const val MIN_COMMANDER_MESSAGES = 3
    private const val RETRY_COOLDOWN_MS = 30L * 60L * 1000L

    private val sensitivePattern = Regex(
        "(?i)(парол|api.?key|ключ\\s+api|токен|cvv|cvc|pin\\s*код|код\\s+подтверждения|" +
            "банковск|номер\\s+карты|диагноз|болезн|лекарств|медицинск|религи|вероисповед|" +
            "политическ|сексуальн|точн\\w*\\s+адрес|домашн\\w*\\s+адрес|координат|геолокац)"
    )

    fun extractIfDue(context: Context): Int {
        val root = context.filesDir
        val state = readJson(root, STATE_PATH)
        val now = System.currentTimeMillis()
        if (now - state.optLong("last_fact_extraction_attempt_at", 0L) < RETRY_COOLDOWN_MS) return 0

        val events = readEvents(root)
        val lastFactEventId = state.optString("last_fact_extraction_event_id")
        val lastFactTs = state.optLong("last_fact_extraction_ts", 0L)
        val cursorIndex = if (lastFactEventId.isBlank()) -1 else
            events.indexOfFirst { it.optString("id") == lastFactEventId }
        val pending = if (cursorIndex >= 0) {
            events.drop(cursorIndex + 1)
        } else {
            events.filter { it.optLong("timestamp", it.optLong("ts", 0L)) > lastFactTs }
        }

        val commanderMessages = pending.filter {
            it.optString("type") == "message" &&
                it.optString("who") == "commander" &&
                it.optString("text").isNotBlank()
        }
        if (commanderMessages.size < MIN_COMMANDER_MESSAGES) return 0

        val config = try {
            JSONObject(SecretStore.readRuntimeConfig(root))
        } catch (e: Exception) {
            Log.w("Ruri", "Fact extraction skipped: model config unavailable", e)
            return 0
        }
        val models = config.optJSONArray("models") ?: return 0
        val current = config.optInt("current", 0).coerceIn(0, (models.length() - 1).coerceAtLeast(0))
        val order = (0 until models.length()).sortedBy { if (it == current) 0 else 1 }
        val transcript = pending.filter { it.optString("type") == "message" }.takeLast(24)
        val commanderIds = commanderMessages.map { it.optString("id") }.filter { it.isNotBlank() }.toSet()
        if (commanderIds.isEmpty()) return 0

        val prompt = buildPrompt(transcript)
        var parsed: JSONObject? = null
        var usedModel = ""
        for (index in order) {
            val model = models.optJSONObject(index) ?: continue
            if (model.optString("api_key").isBlank()) continue
            val response = request(model, prompt) ?: continue
            parsed = parseJson(response)
            if (parsed != null) {
                usedModel = model.optString("name", model.optString("id", "model"))
                break
            }
        }

        if (parsed == null) {
            state.put("last_fact_extraction_attempt_at", now)
            FileManager.write(root, STATE_PATH, state.toString(2))
            return 0
        }

        val factsRaw = FileManager.read(root, FACTS_PATH)
        val factsFile = try {
            if (factsRaw.isBlank()) JSONObject() else JSONObject(factsRaw)
        } catch (e: Exception) {
            preserveCorruptFacts(root, factsRaw)
            state.put("last_fact_extraction_attempt_at", now)
            FileManager.write(root, STATE_PATH, state.toString(2))
            Log.w("Ruri", "Automatic fact extraction stopped: L1 is malformed and was preserved", e)
            return 0
        }
        val facts = factsFile.optJSONArray("facts") ?: JSONArray()
        val candidates = parsed.optJSONArray("facts") ?: JSONArray()
        var added = 0
        var updated = 0

        for (index in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(index) ?: continue
            val text = candidate.optString("text").trim().take(240)
            if (text.length < 5 || sensitivePattern.containsMatchIn(text)) continue

            val confidence = candidate.optDouble("confidence", 0.0)
            val evidence = candidate.optJSONArray("evidence_event_ids") ?: JSONArray()
            val validEvidence = JSONArray()
            for (evidenceIndex in 0 until evidence.length()) {
                val id = evidence.optString(evidenceIndex)
                if (id in commanderIds) validEvidence.put(id)
            }
            val explicit = candidate.optBoolean("explicit", false)
            if (confidence < 0.82 || validEvidence.length() == 0) continue
            if (!explicit && validEvidence.length() < 2) continue

            val normalized = normalize(text)
            var existing: JSONObject? = null
            for (factIndex in 0 until facts.length()) {
                val fact = facts.optJSONObject(factIndex) ?: continue
                if (normalize(fact.optString("text")) == normalized) {
                    existing = fact
                    break
                }
            }

            if (existing != null) {
                existing.put("confidence", maxOf(existing.optDouble("confidence", 0.0), confidence))
                existing.put("updated_at", now)
                existing.put("last_confirmed_at", now)
                existing.put("explicit", existing.optBoolean("explicit", false) || explicit)
                existing.put("source_event_ids", mergeIds(existing.optJSONArray("source_event_ids"), validEvidence))
                updated++
            } else if (facts.length() < 500) {
                facts.put(
                    JSONObject()
                        .put("id", "fact-" + UUID.randomUUID().toString())
                        .put("text", text)
                        .put("category", candidate.optString("category", "general").take(40))
                        .put("confidence", confidence)
                        .put("explicit", explicit)
                        .put("source_event_ids", validEvidence)
                        .put("origin", "automatic_extraction")
                        .put("sensitivity", "normal")
                        .put("created_at", now)
                        .put("updated_at", now)
                        .put("last_confirmed_at", now)
                        .put("source_model", usedModel)
                )
                added++
            }
        }

        val lastCommanderEvent = commanderMessages.last()
        state.put("last_fact_extraction_attempt_at", now)
        state.put("last_fact_extraction_ts", lastCommanderEvent.optLong("timestamp", lastCommanderEvent.optLong("ts", now)))
        state.put("last_fact_extraction_event_id", lastCommanderEvent.optString("id"))
        state.put("last_fact_extraction_model", usedModel)
        factsFile.put("facts", facts)
        factsFile.put("updated_at", now)
        FileManager.write(root, FACTS_PATH, factsFile.toString(2))
        FileManager.write(root, STATE_PATH, state.toString(2))
        FileManager.appendEvent(
            root,
            JSONObject()
                .put("type", "facts_extracted")
                .put("source", "memory")
                .put("added", added)
                .put("updated", updated)
                .put("commander_messages", commanderMessages.size)
                .put("model", usedModel)
                .put("privacy", "local")
        )
        return added + updated
    }

    private fun buildPrompt(events: List<JSONObject>): String {
        val transcript = JSONArray()
        events.forEach { event ->
            transcript.put(
                JSONObject()
                    .put("event_id", event.optString("id"))
                    .put("speaker", event.optString("who"))
                    .put("text", redactForExternalModel(event.optString("text")).take(1200))
            )
        }
        return """
            Извлеки из переписки только устойчивые факты, которые помогут в будущих разговорах:
            предпочтения, долгосрочные проекты, явно заявленные цели, повторяющиеся привычки и важные постоянные решения.
            Учитывай как доказательства только реплики со speaker="commander". Ответы assistant разрешены только как контекст.
            Не выводи предположения из одного случайного сообщения. Не включай пароли, API-ключи, токены,
            платёжные данные, медицинские сведения, религию, политические взгляды, интимные сведения,
            точные адреса/координаты или иные чувствительные личные данные. Не сохраняй временные мелочи.
            Для каждого факта укажи точные event_id исходных реплик Командира.
            Верни только корректный JSON без markdown:
            {"facts":[{"text":"...","category":"preference|project|goal|habit|other","confidence":0.0,"explicit":true,"evidence_event_ids":["evt-..."]}]}
            Если устойчивых фактов нет, верни {"facts":[]}.
            Переписка:
            $transcript
        """.trimIndent()
    }

    private fun request(model: JSONObject, prompt: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val baseUrl = model.optString("base_url").trimEnd('/')
            if (baseUrl.isBlank()) return null
            connection = (URL(baseUrl + "/chat/completions").openConnection() as HttpURLConnection).apply {
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
                .put("temperature", 0.1)
                .put("max_tokens", 900)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return null
            if (status !in 200..299) return null
            JSONObject(raw).optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w("Ruri", "Fact extraction provider request failed", e)
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

    private fun readEvents(root: java.io.File): List<JSONObject> {
        val raw = FileManager.read(root, L0_PATH)
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().filter { it.isNotBlank() }
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .toList()
    }

    private fun readJson(root: java.io.File, path: String): JSONObject {
        val raw = FileManager.read(root, path)
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse {
            Log.w("Ruri", "Fact extraction skipped because " + path + " is invalid", it)
            JSONObject()
        }
    }

    private fun mergeIds(old: JSONArray?, next: JSONArray): JSONArray {
        val result = JSONArray()
        val seen = HashSet<String>()
        old?.let {
            for (index in 0 until it.length()) {
                val value = it.optString(index)
                if (value.isNotBlank() && seen.add(value)) result.put(value)
            }
        }
        for (index in 0 until next.length()) {
            val value = next.optString(index)
            if (value.isNotBlank() && seen.add(value)) result.put(value)
        }
        return result
    }

    private fun redactForExternalModel(value: String): String {
        if (sensitivePattern.containsMatchIn(value)) return "[сообщение скрыто локальным фильтром приватности]"
        return value
            .replace(Regex("(?i)\\b(password|passwd|secret|token|api[_ -]?key)\\b\\s*[:=]\\s*[^\\s,;]+"), "[скрыто]")
            .replace(Regex("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}"), "[email скрыт]")
            .replace(Regex("(?<!\\w)\\+?\\d[\\d\\s().-]{7,}\\d(?!\\w)"), "[номер скрыт]")
    }

    private fun preserveCorruptFacts(root: java.io.File, raw: String) {
        if (raw.isBlank()) return
        try {
            val backup = java.io.File(root, "backup/L1_facts_corrupt_" + System.currentTimeMillis() + ".json")
            backup.parentFile?.mkdirs()
            if (!backup.exists()) backup.writeText(raw, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("Ruri", "Could not preserve damaged L1 facts file", e)
        }
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}
