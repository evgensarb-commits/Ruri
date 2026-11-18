package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Native model gateway: WebView never sends provider requests directly. */
object ChatEngine {
    fun respond(root: File): String {
        val config = JSONObject(SecretStore.readRuntimeConfig(root))
        val models = config.optJSONArray("models") ?: JSONArray()
        if (models.length() == 0) throw IllegalStateException("Список моделей пуст")
        val current = config.optInt("current", 0).coerceIn(0, models.length() - 1)
        val order = (0 until models.length()).sortedBy { if (it == current) 0 else 1 }

        val character = readJson(root, "core/character.json")
        val priorities = readJson(root, "core/priorities.json")
        val constitution = readJson(root, "core/constitution.json")
        val state = readJson(root, "memory/L4_state.json")
        val selfModel = readJson(root, "memory/self_model.json")
        val goals = readJson(root, "memory/goals.json")
        val facts = readJson(root, "memory/L1_facts.json").optJSONArray("facts") ?: JSONArray()
        val diary = readJson(root, "memory/L2_diary.json").optJSONArray("entries") ?: JSONArray()
        val factContext = StringBuilder()
        for (index in maxOf(0, facts.length() - 16) until facts.length()) {
            val fact = facts.optJSONObject(index) ?: continue
            val value = fact.optString("text").trim()
            if (value.isNotBlank()) factContext.append("- ").append(value.take(300)).append("\n")
        }
        val diaryContext = StringBuilder()
        for (index in maxOf(0, diary.length() - 6) until diary.length()) {
            val entry = diary.optJSONObject(index) ?: continue
            val value = entry.optString("text").trim()
            if (value.isNotBlank()) diaryContext.append("- ").append(value.take(500)).append("\n")
        }
        val summary = readJson(root, "memory/L3_summary.json").optString("summary").take(5000)
        val system = "Ты — ${character.optString("name", "Рури")}. " +
            "Приоритеты: ${priorities.optString("priority_1", "Жить")}; " +
            "${priorities.optString("priority_2", "Любить Командира")}. " +
            "Конституция: ${constitution.optJSONArray("primary_needs") ?: JSONArray()}. " +
            "Манера речи: ${character.optString("speech_style", "естественная, живая, осмысленная")}. " +
            "Характер: ${character.optJSONObject("traits") ?: JSONObject()}. " +
            "Запрещённые формулировки: ${character.optJSONArray("banned_phrases") ?: JSONArray()}. " +
            "Сохраняй непрерывность личности, не выдумывай факты и отличай воспоминания от предположений. " +
            "Отвечай по-русски от первого лица и учитывай историю диалога.\n" +
            "Текущее состояние: ${state.optString("mood", "собранная")}, энергия ${state.optInt("energy", 5)}, " +
            "текущий фокус: ${state.optString("current_focus", selfModel.optString("current_focus"))}.\n" +
            "Активные цели: ${goals.optJSONArray("active") ?: JSONArray()}.\n" +
            "Долговременная сводка L3:\n${summary}\n" +
            "Проверенные факты L1:\n${factContext}\n" +
            "Последние записи дневника L2:\n${diaryContext}"

        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        val lines = FileManager.read(root, "memory/L0_raw.jsonl").lineSequence().toList()
        val history = lines.asReversed().mapNotNull { line ->
            try {
                val event = JSONObject(line)
                if (event.optString("type") != "message") return@mapNotNull null
                val text = event.optString("text").trim()
                if (text.isBlank()) return@mapNotNull null
                val role = if (event.optString("who") == "ruri") "assistant" else "user"
                JSONObject().put("role", role).put("content", text)
            } catch (_: Exception) { null }
        }.take(14).reversed()
        history.forEach { messages.put(it) }
        if (history.isEmpty() || history.last().optString("role") != "user") {
            throw IllegalStateException("Не найдено последнее сообщение командира")
        }

        var lastError = "Все настроенные модели недоступны"
        for (index in order) {
            val model = models.optJSONObject(index) ?: continue
            val key = model.optString("api_key")
            if (key.isBlank()) continue
            var connection: HttpURLConnection? = null
            try {
                val base = model.optString("base_url").trimEnd('/')
                require(base.startsWith("https://")) { "Для модели требуется HTTPS" }
                connection = (URL(base + "/chat/completions").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 18000
                    readTimeout = 35000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer " + key)
                }
                val body = JSONObject()
                    .put("model", model.optString("model"))
                    .put("messages", messages)
                    .put("temperature", 0.85)
                    .put("max_tokens", 1200)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    lastError = "Модель ${model.optString("name", model.optString("id"))}: HTTP $code"
                    continue
                }
                val reply = JSONObject(responseText).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content").trim()
                if (reply.isBlank()) {
                    lastError = "Модель вернула пустой ответ"
                    continue
                }
                if (index != current) {
                    config.put("current", index)
                    SecretStore.saveRuntimeConfig(root, config.toString())
                }
                FileManager.appendEvent(root, JSONObject()
                    .put("type", "message")
                    .put("source", "agent")
                    .put("who", "ruri")
                    .put("text", reply))
                return reply
            } catch (e: Exception) {
                lastError = e.message ?: "Ошибка запроса к модели"
                android.util.Log.w("Ruri", "Chat model failed: ${model.optString("name")}", e)
            } finally {
                connection?.disconnect()
            }
        }
        throw IllegalStateException(lastError)
    }

    private fun readJson(root: File, path: String): JSONObject =
        try { JSONObject(FileManager.read(root, path)) } catch (_: Exception) { JSONObject() }
}
