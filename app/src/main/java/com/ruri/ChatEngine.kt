package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import android.os.SystemClock

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
        val notificationContext = FileManager.readLastLines(
            root, "memory/L0_raw.jsonl", maxLines = 6000, maxBytes = 4 * 1024 * 1024
        ).asReversed().mapNotNull { line ->
            try {
                val event = JSONObject(line)
                if (event.optString("type") != "phone_notification") return@mapNotNull null
                "- ${event.optString("package")}: " +
                    event.optString("title") + " — " +
                    event.optString("text")
            } catch (_: Exception) { null }
        }.take(10).asReversed().joinToString("\n")
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
            "Незавершённые задачи: ${state.optJSONArray("active_tasks") ?: JSONArray()}.\n" +
            "Долговременная сводка L3:\n${summary}\n" +
            "Проверенные факты L1:\n${factContext}\n" +
            "Последние записи дневника L2:\n${diaryContext}\n" +
            "Последние доступные уведомления телефона:\n${notificationContext}"

        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        val lines = FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 4000)
        val history = lines.asReversed().mapNotNull { line ->
            try {
                val event = JSONObject(line)
                if (event.optString("type") != "message") return@mapNotNull null
                val text = event.optString("text").trim()
                if (text.isBlank()) return@mapNotNull null
                val role = if (event.optString("who") == "ruri") "assistant" else "user"
                JSONObject().put("role", role).put(
                    "content",
                    text
                )
            } catch (_: Exception) { null }
        }.take(14).reversed()
        if (history.isEmpty() || history.last().optString("role") != "user") {
            throw IllegalStateException("Не найдено последнее сообщение командира")
        }
        val recalled = MemoryRetriever.recall(root, history.last().optString("content"), limit = 6)
        if (recalled.isNotBlank()) {
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", "Ассоциативно найденные воспоминания. Используй их, если они относятся к вопросу. Не выдавай предположения за точные воспоминания; при необходимости обозначай неопределённость.\\n" + recalled)
            )
        }
        history.forEach { messages.put(it) }

        var lastError = "Все настроенные модели недоступны"
        for (index in order) {
            val model = models.optJSONObject(index) ?: continue
            val key = model.optString("api_key")
            if (key.isBlank()) continue
            var connection: HttpURLConnection? = null
            val diagnostic = JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("provider", model.optString("name", model.optString("id")))
                .put("model", model.optString("model"))
                .put("base_url", model.optString("base_url").trimEnd('/'))
                .put("host", "")
                .put("dns_status", "not_started")
                .put("dns_ms", JSONObject.NULL)
                .put("dns_addresses", 0)
                .put("connect_tls_ms", JSONObject.NULL)
                .put("write_ms", JSONObject.NULL)
                .put("response_wait_ms", JSONObject.NULL)
                .put("http_status", JSONObject.NULL)
                .put("stage", "CONFIG")
                .put("result", "failed")
            var stage = "CONFIG"
            var stageStartedAt = 0L
            try {
                val base = model.optString("base_url").trimEnd('/')
                require(base.startsWith("https://")) { "Для модели требуется HTTPS" }
                val endpoint = URL(base + "/chat/completions")
                val host = endpoint.host
                diagnostic.put("host", host)
                stage = "DNS"
                diagnostic.put("stage", stage)
                stageStartedAt = SystemClock.elapsedRealtime()
                val addresses = InetAddress.getAllByName(host)
                diagnostic.put("dns_ms", SystemClock.elapsedRealtime() - stageStartedAt)
                diagnostic.put("dns_addresses", addresses.size)
                diagnostic.put("dns_status", if (addresses.isNotEmpty()) "resolved" else "empty")
                connection = (endpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 18000
                    readTimeout = 35000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer " + key)
                }
                stage = "CONNECT_TLS"
                diagnostic.put("stage", stage)
                val connectStart = SystemClock.elapsedRealtime()
                connection.connect()
                diagnostic.put("connect_tls_ms", SystemClock.elapsedRealtime() - connectStart)
                val body = JSONObject()
                    .put("model", model.optString("model"))
                    .put("messages", messages)
                    .put("temperature", 0.85)
                    .put("max_tokens", 1200)
                stage = "WRITE"
                diagnostic.put("stage", stage)
                val writeStart = SystemClock.elapsedRealtime()
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                diagnostic.put("write_ms", SystemClock.elapsedRealtime() - writeStart)
                stage = "HTTP_RESPONSE"
                diagnostic.put("stage", stage)
                val responseStart = SystemClock.elapsedRealtime()
                val code = connection.responseCode
                diagnostic.put("response_wait_ms", SystemClock.elapsedRealtime() - responseStart)
                diagnostic.put("http_status", code)
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                stage = "READ_RESPONSE"
                diagnostic.put("stage", stage)
                val bodyReadStart = SystemClock.elapsedRealtime()
                val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                diagnostic.put("response_body_ms", SystemClock.elapsedRealtime() - bodyReadStart)
                if (code !in 200..299) {
                    diagnostic.put("stage", "HTTP")
                    diagnostic.put("result", "http_error")
                    NetworkDiagnostics.record(root, diagnostic)
                    lastError = "Модель ${model.optString("name", model.optString("id"))}: HTTP $code"
                    continue
                }
                stage = "PARSE_RESPONSE"
                diagnostic.put("stage", stage)
                val reply = JSONObject(responseText).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content").trim()
                if (reply.isBlank()) {
                    diagnostic.put("result", "empty_reply")
                    NetworkDiagnostics.record(root, diagnostic)
                    lastError = "Модель вернула пустой ответ"
                    continue
                }
                diagnostic.put("stage", "COMPLETE")
                diagnostic.put("result", "success")
                NetworkDiagnostics.record(root, diagnostic)
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
                diagnostic.put("stage", stage)
                if (stage == "DNS" && diagnostic.isNull("dns_ms")) {
                    diagnostic.put("dns_ms", SystemClock.elapsedRealtime() - stageStartedAt)
                }
                diagnostic.put("result", if (e is SocketTimeoutException) "timeout" else "failed")
                diagnostic.put("error_type", e.javaClass.simpleName)
                if (e is UnknownHostException) diagnostic.put("dns_status", "failed")
                if (e is SocketTimeoutException) diagnostic.put("timeout_stage", stage)
                NetworkDiagnostics.record(root, diagnostic)
                lastError = when {
                    e is UnknownHostException -> "DNS: не удалось разрешить адрес сервера"
                    e is SocketTimeoutException -> "Тайм-аут на этапе $stage"
                    else -> e.message ?: "Ошибка запроса к модели"
                }
                android.util.Log.w("Ruri", "Chat model failed at $stage: ${e.javaClass.simpleName}")
            } finally {
                connection?.disconnect()
            }
        }
        throw IllegalStateException(lastError)
    }

    private fun readJson(root: File, path: String): JSONObject =
        try { JSONObject(FileManager.read(root, path)) } catch (_: Exception) { JSONObject() }
}
