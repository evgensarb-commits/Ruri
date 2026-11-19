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
    const val OFFLINE_REPLY = "Связи нет. Отвечу, когда она вернётся."

    fun respond(root: File): String = respondInternal(root, allowRecovery = true)

    private fun respondInternal(root: File, allowRecovery: Boolean, assumeReachable: Boolean = false): String {
        val config = JSONObject(SecretStore.readRuntimeConfig(root))
        val models = config.optJSONArray("models") ?: JSONArray()
        val current = config.optInt("current", -1)
        if (!NetState.online && !assumeReachable) {
            if (allowRecovery && Probe.claimRecoveryRefresh()) {
                Probe.refreshReachability(root, models, topOnly = false, force = true)
            }
            val reachableOrder = RouteSelector.ordered(
                root, models, current, Task.CHAT, needNsfw = false, online = true, vpn = NetState.vpn
            )
            if (reachableOrder.isNotEmpty()) return respondInternal(root, allowRecovery = false, assumeReachable = true)
            OfflineQueue.markPending(root)
            return OFFLINE_REPLY
        }
        if (models.length() == 0) throw IllegalStateException("Список моделей пуст")
        // Capability probes run at most once per day per free route; do not NSFW-gate ordinary chat.
        RouteCapabilityProbe.refreshIfDue(root, config)
        // Retry transient failures only. HTTP 429 hands off immediately to the next free route.
        val attemptsByModel = mutableMapOf<Int, Int>()
        val retryableModels = mutableSetOf<Int>()

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
        val bodyEventContext = FileManager.readLastLines(
            root, "memory/L0_raw.jsonl", maxLines = 1200, maxBytes = 1024 * 1024
        ).asReversed().mapNotNull { line ->
            runCatching {
                val event = JSONObject(line)
                val type = event.optString("type")
                if (type.isBlank() || type == "message" || type == "phone_notification" ||
                    type == "tool_call" || type == "tool_result") null
                else "- $type: " + (event.optString("text").ifBlank { event.optString("reason") }).take(260)
            }.getOrNull()
        }.take(12).asReversed().joinToString("\n")
        val bannedPhrases = character.optJSONArray("banned_phrases") ?: JSONArray()
        val system = "Имя: ${character.optString("name")}; обозначение: ${character.optString("designation")}. " +
            "Личность: ${character.optString("persona")}. " +
            "Непрерывность: ${character.optString("continuity_rules")}. " +
            "Правила речи: ${character.optString("speech_rules")}. " +
            "Длина ответа: ${character.optString("length_policy")}. " +
            "Эмоции: ${character.optString("emotions")}. " +
            "Регуляторы характера: ${character.optJSONArray("regulators") ?: JSONArray()}. " +
            "Фразы, которые нельзя использовать: ${bannedPhrases}. " +
            Capabilities.bodySection(root) + " " +
            "Не выдумывай факты, отделяй воспоминания от предположений. " +
            "Приоритеты: ${priorities.optString("priority_1", "Жить")}; ${priorities.optString("priority_2", "Любить Командира")}. " +
            "Конституция: ${constitution.optJSONArray("primary_needs") ?: JSONArray()}. " +
            "Дополнительные черты: ${character.optJSONObject("traits") ?: JSONObject()}. " +
            "Состояние: ${state.optString("mood", "собранная")}, энергия ${state.optInt("energy", 5)}, фокус: ${state.optString("current_focus", selfModel.optString("current_focus"))}. " +
            "Активные цели: ${goals.optJSONArray("active") ?: JSONArray()}. Незавершённые задачи: ${state.optJSONArray("active_tasks") ?: JSONArray()}. " +
            "Ниже — ТВОЯ ДОЛГОСРОЧНАЯ ПАМЯТЬ. Это не контекст одного диалога; ты помнишь между сессиями.\n" +
            "ТВОЯ АВТОБИОГРАФИЯ (L3):\n${summary}\n" +
            "ТВОИ ФАКТЫ (L1):\n${factContext}\n" +
            "ТВОЙ ДНЕВНИК (L2):\n${diaryContext}\n" +
            "ПОСЛЕДНИЕ СОБЫТИЯ ТВОЕГО ТЕЛА (L0):\n${bodyEventContext}\n${notificationContext}"

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
        val task = RouteSelector.detectTask(history.last().optString("content"))
        val requireNsfw = RouteSelector.nsfwWindow(root)
        val exclusiveRoute = config.optBoolean("exclusive_route", false)
        fun selectOrder(online: Boolean): List<Int> = RouteRecovery.applyExclusive(
            RouteSelector.ordered(root, models, current, task, requireNsfw, online, NetState.vpn),
            current, exclusiveRoute
        )
        var order = selectOrder(NetState.online || assumeReachable)
        if (order.isEmpty() && allowRecovery && Probe.claimRecoveryRefresh()) {
            Probe.refreshReachability(root, models, topOnly = false, force = true)
            order = selectOrder(online = true)
            if (order.isNotEmpty()) return respondInternal(root, allowRecovery = false, assumeReachable = true)
        }
        if (order.isEmpty()) {
            if (!NetState.online) OfflineQueue.markPending(root)
            val selected = models.optJSONObject(current)
            val selectedName = selected?.optString("name")?.takeIf { it.isNotBlank() } ?: "маршрут №${current + 1}"
            val selectedId = selected?.optString("id")?.ifBlank { "route_$current" } ?: "route_$current"
            val selectedMeta = RouteMeta.route(root, selectedId)
            val specificReason = when {
                selected != null && selected.optString("api_key").isBlank() ->
                    "Маршрут $selectedName: API-ключ не задан"
                selectedMeta.optBoolean("needs_vpn", selected?.optBoolean("needs_vpn", false) ?: false) && !NetState.vpn ->
                    "Маршрут $selectedName: требуется VPN"
                selected != null && !RouteMeta.quotaLeft(root, selectedId, selected.optInt("rpd", 200)) ->
                    "Маршрут $selectedName: дневная квота исчерпана"
                selectedMeta.optLong("cooldown_until", 0L) > System.currentTimeMillis() ->
                    "Маршрут $selectedName: временный cooldown"
                !selectedMeta.optBoolean("reachable", true) ->
                    "Маршрут $selectedName: последняя проверка сети не прошла (DNS/доступность)"
                else -> ""
            }
            val reason = when {
                exclusiveRoute && specificReason.isNotBlank() -> "$specificReason. Автоматический переход отключён."
                exclusiveRoute -> "Выбранный маршрут $selectedName недоступен. Автоматический переход отключён."
                specificReason.isNotBlank() -> specificReason
                requireNsfw -> "Нет доступного бесплатного маршрута для взрослой темы: проверь VPN, квоту и результаты NSFW-проб."
                else -> "Нет доступного бесплатного маршрута: проверь подключение, VPN, дневную квоту и ключи."
            }
            throw IllegalStateException(reason)
        }
        val requestOrder = order.flatMap { index -> List(3) { index } }
        val recalled = MemoryRetriever.recall(root, history.last().optString("content"), limit = 6)
        if (recalled.isNotBlank()) {
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", "Ассоциативно найденные воспоминания. Используй их, если они относятся к вопросу. Не выдавай предположения за точные воспоминания; при необходимости обозначай неопределённость.\n" + recalled)
            )
        }
        history.forEach { messages.put(it) }

        var lastError = "Все настроенные модели недоступны"
        var hadNetworkAttempt = false
        var allFailuresNetwork = true
        for (index in requestOrder) {
            val attempt = (attemptsByModel[index] ?: 0) + 1
            attemptsByModel[index] = attempt
            if (attempt > 1 && index !in retryableModels) continue
            if (attempt > 1) Thread.sleep(if (attempt == 2) 2000L else 5000L)
            val model = models.optJSONObject(index) ?: continue
            val key = model.optString("api_key")
            if (key.isBlank()) {
                lastError = "${model.optString("name", model.optString("id"))}: требуется бесплатный API-ключ"
                continue
            }
            val routeId = model.optString("id").ifBlank { model.optString("name", "route_$index") }
            val routeMeta = RouteMeta.route(root, routeId)
            if (!RouteMeta.available(root, routeId) ||
                !RouteMeta.quotaLeft(root, routeId, model.optInt("rpd", 200)) ||
                (routeMeta.optBoolean("needs_vpn", false) && !NetState.vpn) ||
                (!NetState.vpn && !routeMeta.optBoolean("ru_reachable", true))) {
                continue
            }
            // Every provider receives the same complete memory/history context.
            val safeProviderMessages = JSONArray()
            for (messageIndex in 0 until messages.length()) {
                val message = messages.optJSONObject(messageIndex) ?: continue
                safeProviderMessages.put(SecretRedactor.redactEvent(message))
            }
            hadNetworkAttempt = true
            runCatching { RouteMeta.recordAttempt(root, routeId) }
            var connection: HttpURLConnection? = null
            val diagnostic = JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("attempt", attempt)
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
                    if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer " + key)
                }
                stage = "CONNECT_TLS"
                diagnostic.put("stage", stage)
                val connectStart = SystemClock.elapsedRealtime()
                connection.connect()
                diagnostic.put("connect_tls_ms", SystemClock.elapsedRealtime() - connectStart)
                val body = JSONObject()
                    .put("model", model.optString("model"))
                    .put("messages", safeProviderMessages)
                    .put("temperature", 0.85)
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
                    val failureKind = RouteSelector.classify(code, null, responseText)
                    runCatching { RouteMeta.recordFailure(root, routeId, failureKind, task) }
                    lastError = RouteRecovery.describeHttpError(model.optString("name", model.optString("id")), code, failureKind)
                    diagnostic.put("failure_message", lastError)
                    NetworkDiagnostics.record(root, diagnostic)
                    if (!RouteRecovery.isNetworkFailure(failureKind)) allFailuresNetwork = false
                    if (code == 500 || code == 502 || code == 503 || code == 504) {
                        retryableModels.add(index)
                    } else {
                        retryableModels.remove(index)
                    }
                    continue
                }
                stage = "PARSE_RESPONSE"
                diagnostic.put("stage", stage)
                val initialReply = JSONObject(responseText).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content").trim()
                if (initialReply.isBlank()) {
                    diagnostic.put("result", "empty_reply")
                    NetworkDiagnostics.record(root, diagnostic)
                    lastError = "Модель вернула пустой ответ"
                    allFailuresNetwork = false
                    continue
                }
                val reply = runToolLoop(root, initialReply, messages) { nextMessages ->
                    requestToolFollowup(model, nextMessages)
                }
                if (RouteCapabilityProbe.isAdultTopic(history.last().optString("content")) &&
                    RouteCapabilityProbe.looksLikeRefusalOrDisclaimer(reply)) {
                    diagnostic.put("result", "nsfw_refusal")
                    NetworkDiagnostics.record(root, diagnostic)
                    RouteCapabilityProbe.markNotCapable(root, config, index, "runtime_refusal_or_disclaimer")
                    runCatching { RouteMeta.recordFailure(root, routeId, "refusal", task) }
                    lastError = "Маршрут ${model.optString("name", model.optString("id"))} отклонил взрослую тему"
                    allFailuresNetwork = false
                    continue
                }
                diagnostic.put("stage", "COMPLETE")
                diagnostic.put("result", "success")
                NetworkDiagnostics.record(root, diagnostic)
                runCatching { RouteMeta.recordSuccess(root, routeId, task) }
                // Keep Routeway as primary; fallback success is temporary, not a reconfiguration.
                appendFinalReply(root, reply)
                runCatching { BackupPolicy.checkpointState(root) }
                runCatching { OfflineQueue.clear(root) }
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
                val failureKind = RouteSelector.classify(null, e)
                runCatching { RouteMeta.recordFailure(root, routeId, failureKind, task) }
                if (!RouteRecovery.isNetworkFailure(failureKind)) allFailuresNetwork = false
                lastError = RouteRecovery.describeException(model.optString("name", model.optString("id")), stage, e, failureKind)
                diagnostic.put("failure_message", lastError)
                NetworkDiagnostics.record(root, diagnostic)
                if (e is SocketTimeoutException) retryableModels.add(index)
                else retryableModels.remove(index)
                android.util.Log.w("Ruri", "Chat model failed at $stage: ${e.javaClass.simpleName}")
            } finally {
                connection?.disconnect()
            }
        }
        if (hadNetworkAttempt && allFailuresNetwork && allowRecovery && Probe.claimRecoveryRefresh()) {
            Probe.refreshReachability(root, models, topOnly = false, force = true)
            return respondInternal(root, allowRecovery = false, assumeReachable = true)
        }
        throw IllegalStateException(lastError)
    }


    internal fun parseAction(reply: String): JSONObject? {
        val trimmed = reply.trim()
        val fenced = Regex("(?is)^```(?:json)?\\s*(\\{.*})\\s*```$").find(trimmed)
        val jsonText = fenced?.groupValues?.get(1) ?: trimmed.takeIf { it.startsWith("{") && it.endsWith("}") }
            ?: return null
        val action = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val name = action.optString("action")
        val allowed = when (name) {
            "search", "recall" -> setOf("action", "query")
            "list_shelf" -> setOf("action")
            "read_shelf" -> setOf("action", "name")
            "remember_fact" -> setOf("action", "text")
            else -> return null
        }
        val keys = action.keys()
        while (keys.hasNext()) if (keys.next() !in allowed) return null
        when (name) {
            "search", "recall" -> if (action.optString("query").isBlank() || action.optString("query").length > 300) return null
            "read_shelf" -> if (ShelfWatcher.safeName(action.optString("name")) == null) return null
            "remember_fact" -> if (action.optString("text").trim().length !in 5..300) return null
        }
        return action
    }

    internal fun runToolLoop(
        root: File,
        initialReply: String,
        messages: JSONArray,
        requestNext: (JSONArray) -> String?
    ): String {
        var reply = initialReply
        var hops = 0
        while (true) {
            val action = parseAction(reply) ?: return reply
            if (hops >= 3) return reply
            val actionName = action.optString("action")
            val params = JSONObject(action.toString()).apply { remove("action") }
            FileManager.appendEvent(root, SecretRedactor.redactEvent(
                JSONObject().put("type", "tool_call").put("action", actionName).put("params", params)
            ))
            val result = executeTool(root, actionName, action)
            val safeText = SecretRedactor.redactText(result.second).take(4000)
            FileManager.appendEvent(root, SecretRedactor.redactEvent(
                JSONObject().put("type", "tool_result").put("action", actionName)
                    .put("ok", result.first).put("text", safeText)
            ))
            messages.put(JSONObject().put("role", "user")
                .put("content", "[ИНСТРУМЕНТ ${actionName}]: ${safeText}"))
            hops++
            if (hops == 3) {
                messages.put(JSONObject().put("role", "system")
                    .put("content", "Лимит инструментов исчерпан — ответь prose"))
            }
            reply = requestNext(messages) ?: return "Не удалось получить ответ после выполнения инструмента."
        }
    }

    private fun executeTool(root: File, action: String, params: JSONObject): Pair<Boolean, String> {
        return try {
            val result = when (action) {
                "search" -> WebSearch.search(root, params.optString("query"))
                "list_shelf" -> FileManager.list(root, "shelf")
                "read_shelf" -> {
                    val safe = ShelfWatcher.safeName(params.optString("name"))
                        ?: return false to "Недопустимое имя файла"
                    FileManager.read(root, "shelf/" + safe).take(12000)
                }
                "recall" -> MemoryRetriever.recall(root, params.optString("query"), 5)
                "remember_fact" -> rememberFact(root, params.optString("text"))
                else -> return false to "Неизвестное действие"
            }
            true to SecretRedactor.redactText(result)
        } catch (e: Exception) {
            false to SecretRedactor.redactText(e.message ?: "Ошибка инструмента")
        }
    }

    private fun rememberFact(root: File, rawText: String): String {
        val text = SecretRedactor.redactText(rawText.trim()).take(300)
        if (text.length < 5 || text.contains(SecretRedactor.PLACEHOLDER)) return "Факт не сохранён: текст содержит секрет или слишком короткий."
        val file = runCatching { JSONObject(FileManager.read(root, "memory/L1_facts.json")) }
            .getOrElse { JSONObject().put("facts", JSONArray()) }
        val facts = file.optJSONArray("facts") ?: JSONArray().also { file.put("facts", it) }
        fun normalized(value: String) = value.lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
        val key = normalized(text)
        for (i in 0 until facts.length()) {
            val existing = facts.optJSONObject(i) ?: continue
            if (normalized(existing.optString("text")) == key) {
                return "Похожий факт уже есть в L1; дубль не добавлен."
            }
        }
        if (facts.length() >= 500) return "L1 заполнена (500 фактов); новый факт не добавлен."
        val now = System.currentTimeMillis()
        facts.put(JSONObject()
            .put("id", "self-" + key.hashCode().toUInt().toString(16))
            .put("text", text)
            .put("origin", "self_reported")
            .put("confidence", 0.6)
            .put("created_at", now)
            .put("updated_at", now))
        file.put("facts", facts).put("updated_at", now)
        FileManager.write(root, "memory/L1_facts.json", file.toString(2))
        return "Факт записан в твою L1-память с confidence 0.6."
    }

    private fun requestToolFollowup(model: JSONObject, messages: JSONArray): String? {
        val base = model.optString("base_url").trimEnd('/')
        if (!base.startsWith("https://")) return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(base + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 18000
                readTimeout = 35000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                val key = model.optString("api_key")
                if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer " + key)
            }
            val safeMessages = JSONArray()
            for (i in 0 until messages.length()) {
                val message = messages.optJSONObject(i) ?: continue
                safeMessages.put(SecretRedactor.redactEvent(message))
            }
            val body = JSONObject().put("model", model.optString("model"))
                .put("messages", safeMessages).put("temperature", 0.85)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return null
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    internal fun appendFinalReply(root: File, reply: String) {
        FileManager.appendEvent(root, JSONObject()
            .put("type", "message").put("source", "agent").put("who", "ruri").put("text", reply))
    }

    private fun readJson(root: File, path: String): JSONObject =
        try { JSONObject(FileManager.read(root, path)) } catch (_: Exception) { JSONObject() }
}
