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
    @Volatile private var lastSearchSourceUrls = JSONArray()
    @Volatile private var lastFinalMessageId: String? = null

    fun respond(root: File): String {
        lastFinalMessageId = null
        setTurnInFlight(root, true)
        return try { respondInternal(root, allowRecovery = true) }
        finally { setTurnInFlight(root, false) }
    }

    internal fun setTurnInFlight(root: File, value: Boolean) {
        val state = readJson(root, "memory/L4_state.json")
        state.put("turn_in_flight", value)
        FileManager.write(root, "memory/L4_state.json", state.toString(2))
    }

    internal fun lastSavedReplyId(): String? = lastFinalMessageId

    internal fun shouldIncludeInChatHistory(root: File, event: JSONObject): Boolean =
        event.optString("type") == "message" && !Tombstone.isDeleted(root, event.optString("id"))

    private fun respondInternal(root: File, allowRecovery: Boolean, assumeReachable: Boolean = false): String {
        val config = JSONObject(SecretStore.readRuntimeConfig(root))
        val models = config.optJSONArray("models") ?: JSONArray()
        val current = config.optInt("current", -1)
        if (!NetState.online && !assumeReachable) {
            if (allowRecovery && Probe.claimRecoveryRefresh()) {
                Probe.refreshReachability(root, models, topOnly = false, force = true)
            }
            val reachableOrder = RouteSelector.orderedIndices(
                root, models, current, Task.CHAT, online = true, vpn = NetState.vpn
            )
            if (reachableOrder.isNotEmpty()) return respondInternal(root, allowRecovery = false, assumeReachable = true)
            OfflineQueue.markPending(root)
            val selected = models.optJSONObject(current)
            val name = selected?.optString("name")?.takeIf { it.isNotBlank() } ?: "выбранный маршрут"
            val id = selected?.optString("id")?.ifBlank { "route_$current" } ?: "route_$current"
            val meta = RouteMeta.route(root, id)
            val reason = when {
                selected == null -> "Нет доступного маршрута: список бесплатных моделей пуст"
                selected.optString("api_key").isBlank() -> "Маршрут $name: API-ключ не задан"
                else -> "Маршрут $name: Android сообщает, что сетевого подключения нет"
            }
            return reason
        }
        if (models.length() == 0) {
            OfflineQueue.markPending(root)
            throw IllegalStateException("Нет настроенных бесплатных маршрутов с полной памятью")
        }
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
            if (entry.optString("id").isNotBlank() && Tombstone.isDeleted(root, entry.optString("id"))) continue
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
            EmotionEngine.promptSection(root) + " " +
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
                if (!shouldIncludeInChatHistory(root, event)) return@mapNotNull null
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
        val commanderText = history.last().optString("content")
        EmotionEngine.onCommanderMessage(root, commanderText)
        val task = RouteSelector.detectTask(commanderText)
        fun selectOrder(online: Boolean): List<Int> = RouteSelector.orderedIndices(root, models, current, task, online, NetState.vpn)
        var order = selectOrder(NetState.online || assumeReachable)
        if (order.isEmpty() && allowRecovery && Probe.claimRecoveryRefresh()) {
            Probe.refreshReachability(root, models, topOnly = false, force = true)
            order = selectOrder(online = true)
            if (order.isNotEmpty()) return respondInternal(root, allowRecovery = false, assumeReachable = true)
        }
        if (order.isEmpty()) {
            OfflineQueue.markPending(root)
            val selected = models.optJSONObject(current)
            val selectedName = selected?.optString("name")?.takeIf { it.isNotBlank() } ?: "маршрут №${current + 1}"
            val selectedId = selected?.optString("id")?.ifBlank { "route_$current" } ?: "route_$current"
            val selectedMeta = RouteMeta.route(root, selectedId)
            val specificReason = when {
                selected != null && selected.optString("api_key").isBlank() ->
                    "Маршрут $selectedName: API-ключ не задан"
                else -> ""
            }
            val reason = when {
                specificReason.isNotBlank() -> specificReason
                else -> "Нет настроенного бесплатного маршрута с API-ключом."
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
        if (!hasFullMemoryContext(messages)) {
            OfflineQueue.markPending(root)
            throw IllegalStateException("Полная память не собрана; запрос к модели не отправлен")
        }

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
            // No preflight gates: attempt every configured free route; telemetry only.
            // Every provider receives the same complete memory/history context.
            val safeProviderMessages = providerMessagesWithFullMemory(messages)
                ?: run {
                    OfflineQueue.markPending(root)
                    throw IllegalStateException("Полная память не прошла проверку; запрос к маршруту не отправлен")
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
                    val errorUsage = runCatching { JSONObject(responseText).optJSONObject("usage") }.getOrNull()
                    val errorTokens = errorUsage?.optLong("total_tokens", -1L)?.takeIf { it >= 0L }
                        ?: ((errorUsage?.optLong("prompt_tokens", 0L) ?: 0L) + (errorUsage?.optLong("completion_tokens", 0L) ?: 0L))
                    if (errorTokens > 0) RouteMeta.addTokens(root, routeId, errorTokens)
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
                val responseJson = JSONObject(responseText)
            val usage = responseJson.optJSONObject("usage")
            val tokenCount = usage?.optLong("total_tokens", -1L)?.takeIf { it >= 0L }
                ?: ((usage?.optLong("prompt_tokens", 0L) ?: 0L) + (usage?.optLong("completion_tokens", 0L) ?: 0L))
            if (tokenCount > 0) RouteMeta.addTokens(root, routeId, tokenCount)
            val initialReply = responseJson.getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content").trim()
                if (initialReply.isBlank()) {
                    diagnostic.put("result", "empty_reply")
                    NetworkDiagnostics.record(root, diagnostic)
                    lastError = "Модель вернула пустой ответ"
                    allFailuresNetwork = false
                    continue
                }
                val meta = JSONObject()
                lastSearchSourceUrls = JSONArray()
                val rawReply = ToolLoop.unwrapDiaryReply(root, initialReply, messages, meta) { nextMessages ->
                    requestToolFollowup(model, nextMessages, root, routeId)
                }
                val (cleanedReply, emotionDelta) = EmotionEngine.parseAndStrip(rawReply)
                val emotion = if (emotionDelta != null) EmotionEngine.applyDelta(root, emotionDelta, "chat") else null
                var finalReply = cleanedReply
                var guard = 0
                while (guard < 2) {
                    val (clean, diaryTexts, toolActions) = ToolLoop.stripActions(finalReply)
                    diaryTexts.forEach { Diary.append(root, it, "chat_write_diary", emotion, "thought") }
                    if (toolActions.isEmpty()) { finalReply = clean; break }
                    toolActions.forEach { action ->
                        val name = action.optString("action")
                        val toolResult = executeTool(root, name, action)
                        FileManager.appendEvent(root, SecretRedactor.redactEvent(JSONObject().put("type", "tool_call").put("action", name).put("params", action)))
                        FileManager.appendEvent(root, SecretRedactor.redactEvent(JSONObject().put("type", "tool_result").put("action", name).put("ok", toolResult.first).put("text", toolResult.second.take(4000))))
                        messages.put(JSONObject().put("role", "user").put("content", "[ИНСТРУМЕНТ $name]: ${toolResult.second.take(4000)}"))
                    }
                    finalReply = requestToolFollowup(model, messages) ?: clean
                    guard++
                }
                val (cleanFinal, tailDiary, _) = ToolLoop.stripActions(finalReply)
                tailDiary.forEach { Diary.append(root, it, "chat_write_diary", emotion, "thought") }
                val reply = cleanFinal.ifBlank { finalReply }
                if (recordAdultRefusal(
                        root = root,
                        routeId = routeId,
                        task = task,
                        commanderText = commanderText,
                        reply = reply,
                        diagnostic = diagnostic
                    )) {
                    lastError = "Маршрут ${model.optString("name", model.optString("id"))} отклонил взрослую тему"
                    allFailuresNetwork = false
                    continue
                }
                diagnostic.put("stage", "COMPLETE")
                diagnostic.put("result", "success")
                NetworkDiagnostics.record(root, diagnostic)
                runCatching { RouteMeta.recordSuccess(root, routeId, task) }
                // Never persist a raw action object as a chat message.
                if (reply.isBlank() || ToolLoop.isActionJson(reply)) {
                    throw IllegalStateException("Модель не вернула обычный текстовый ответ после инструментов")
                }
                // Keep Routeway as primary; fallback success is temporary, not a reconfiguration.
                appendFinalReply(
                    root, reply, emotion, meta,
                    model.optString("id", model.optString("name", "маршрут"))
                )
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
        OfflineQueue.markPending(root)
        throw IllegalStateException(lastError)
    }


    internal fun providerMessagesWithFullMemory(messages: JSONArray): JSONArray? {
        if (!hasFullMemoryContext(messages)) return null
        val safe = JSONArray()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            safe.put(SecretRedactor.redactEvent(message))
        }
        return safe
    }

    internal fun hasFullMemoryContext(messages: JSONArray): Boolean {
        if (messages.length() < 2) return false
        val system = messages.optJSONObject(0)?.optString("content").orEmpty()
        val hasMemoryLayers = listOf("L0", "L1", "L2", "L3").all(system::contains)
        val hasRecallTool = system.contains("recall", ignoreCase = true)
        val hasConversation = (1 until messages.length()).any {
            val role = messages.optJSONObject(it)?.optString("role")
            role == "user" || role == "assistant"
        }
        return hasMemoryLayers && hasRecallTool && hasConversation
    }

    internal fun parseAction(reply: String): JSONObject? {
        val trimmed = reply.trim()
        val fenced = Regex("(?is)^```(?:json)?\\s*(\\{.*\\})\\s*```$").find(trimmed)
        val jsonText = fenced?.groupValues?.get(1) ?: trimmed.takeIf { it.startsWith("{") && it.endsWith("}") }
            ?: return null
        val action = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val name = action.optString("action")
        val allowed = when (name) {
            "search", "recall" -> setOf("action", "query")
            "read_url" -> setOf("action", "url")
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
            "read_url" -> {
                val url = action.optString("url").trim()
                if (url.length > 2048 || !url.matches(Regex("(?i)^https?://.+"))) return null
            }
            "remember_fact" -> if (action.optString("text").trim().length !in 5..300) return null
        }
        return action
    }

    internal fun runToolLoop(
        root: File,
        initialReply: String,
        messages: JSONArray,
        meta: JSONObject? = null,
        requestNext: (JSONArray) -> String?
    ): String = ToolLoop.runToolLoop(root, initialReply, messages, meta, requestNext = requestNext)

    internal fun captureSearchUrls(raw: String) {
        lastSearchSourceUrls = extractSourceUrls(raw)
    }

    internal fun clearSourceUrls() { lastSearchSourceUrls = JSONArray() }

    internal fun sourceUrlsSnapshot(): JSONArray = JSONArray(lastSearchSourceUrls.toString())

    internal fun isSuccessfulSearchResult(raw: String): Boolean {
        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        if (parsed.has("error")) return false
        return (parsed.optJSONArray("results")?.length() ?: 0) > 0
    }

    internal fun recordAdultRefusal(
        root: File,
        routeId: String,
        task: Task,
        commanderText: String,
        reply: String,
        diagnostic: JSONObject,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (!RouteSelector.isAdultTopic(commanderText) ||
            !RouteSelector.looksLikeRefusalOrDisclaimer(reply)) return false

        diagnostic.put("result", "nsfw_refusal")
        NetworkDiagnostics.record(root, diagnostic)
        FileManager.appendEvent(root, JSONObject()
            .put("type", "nsfw_refusal")
            .put("route", routeId)
            .put("snippet", SecretRedactor.redactText(reply).take(300))
            .put("ts", now))
        runCatching { RouteMeta.recordFailure(root, routeId, "refusal", task) }
        return true
    }

    internal fun executeTool(root: File, action: String, params: JSONObject): Pair<Boolean, String> {
        return try {
            when (action) {
                "search" -> {
                    val raw = WebSearch.search(root, params.optString("query"))
                    val ok = isSuccessfulSearchResult(raw)
                    EmotionEngine.onSearchResult(root, ok)
                    ok to SecretRedactor.redactText(raw)
                }
                "read_url" -> {
                    val (ok, text) = UrlReader.read(root, params.optString("url"))
                    ok to SecretRedactor.redactText(text)
                }
                "list_shelf" -> true to SecretRedactor.redactText(FileManager.list(root, "shelf"))
                "read_shelf" -> {
                    val safe = ShelfWatcher.safeName(params.optString("name"))
                        ?: return false to "Недопустимое имя файла"
                    true to SecretRedactor.redactText(shelfText(root, safe).take(12000))
                }
                "recall" -> true to SecretRedactor.redactText(MemoryRetriever.recall(root, params.optString("query"), 5))
                "remember_fact" -> true to SecretRedactor.redactText(rememberFact(root, params.optString("text")))
                else -> false to "Неизвестное действие"
            }
        } catch (e: Exception) {
            false to SecretRedactor.redactText(e.message ?: "Ошибка инструмента")
        }
    }

    internal fun shelfText(root: File, name: String): String {
        val safe = ShelfWatcher.safeName(name) ?: return "Недопустимое имя файла"
        val file = File(root, "shelf/" + safe)
        if (!file.isFile) return "Файл не найден"
        val bytes = runCatching { file.readBytes() }.getOrElse { return "Файл недоступен" }
        if (Vision.mimeOf(bytes) != null) {
            return FileManager.read(root, "shelf/.desc/" + safe + ".txt")
                .takeIf { it.isNotBlank() }?.take(4000)
                ?: "[изображение без описания: vision недоступен]"
        }
        return FileManager.read(root, "shelf/" + safe).take(12000)
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

    internal fun requestToolFollowup(model: JSONObject, messages: JSONArray, root: File? = null, routeId: String = ""): String? {
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
            val safeMessages = providerMessagesWithFullMemory(messages) ?: return null
            val body = JSONObject().put("model", model.optString("model"))
                .put("messages", safeMessages).put("temperature", 0.85)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return null
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            val response = JSONObject(raw)
            val usage = response.optJSONObject("usage")
            val tokens = usage?.optLong("total_tokens", -1L)?.takeIf { it >= 0L }
                ?: ((usage?.optLong("prompt_tokens", 0L) ?: 0L) + (usage?.optLong("completion_tokens", 0L) ?: 0L))
            if (root != null && routeId.isNotBlank() && tokens > 0) RouteMeta.addTokens(root, routeId, tokens)
            response.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    internal fun appendFinalReply(
        root: File,
        reply: String,
        emotion: JSONObject? = null,
        meta: JSONObject? = null,
        routeName: String = "маршрут"
    ): String {
        val sourceUrls = meta?.optJSONArray("source_urls") ?: JSONArray()
        val searchOk = meta?.takeIf { it.has("search_ok") }?.optBoolean("search_ok")
        return appendFinalReplyInternal(root, reply, routeName, sourceUrls, emotion, searchOk,
            includeEmptySources = meta?.has("source_urls") == true)
    }

    internal fun appendFinalReply(
        root: File,
        reply: String,
        routeName: String,
        sourceUrls: JSONArray = JSONArray(),
        emotion: JSONObject? = null
    ): String = appendFinalReplyInternal(root, reply, routeName, sourceUrls, emotion, null)

    private fun appendFinalReplyInternal(
        root: File,
        reply: String,
        routeName: String,
        sourceUrls: JSONArray,
        emotion: JSONObject?,
        searchOk: Boolean?,
        includeEmptySources: Boolean = false
    ): String {
        val id = java.util.UUID.randomUUID().toString()
        val event = JSONObject().put("id", id).put("type", "message")
            .put("source", "agent").put("who", "ruri").put("text", reply).put("route", routeName)
        if (sourceUrls.length() > 0 || includeEmptySources) event.put("source_urls", JSONArray(sourceUrls.toString()))
        if (searchOk != null) event.put("search_ok", searchOk)
        if (emotion != null) {
            event.put("emotion_before", emotion.optJSONObject("before"))
                .put("emotion_delta", emotion.optJSONObject("delta"))
                .put("emotion_after", emotion.optJSONObject("after"))
                .put("mood_label", emotion.optString("mood"))
        }
        val saved = JSONObject(FileManager.appendEvent(root, event))
        Diary.append(root, reply, "ответ_командиру", emotion, "said")
        val savedId = saved.optString("id").ifBlank { id }
        Receipts.delivered(root, savedId)
        lastFinalMessageId = savedId
        val lastCmd = FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 500)
            .asReversed().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .firstOrNull { it.optString("type") == "message" && it.optString("who") == "commander" &&
                !Tombstone.isDeleted(root, it.optString("id")) }
        if (lastCmd != null) {
            val state = readJson(root, "memory/L4_state.json")
            state.put("last_seen_commander", lastCmd.optLong("ts",
                lastCmd.optLong("timestamp", System.currentTimeMillis())))
            FileManager.write(root, "memory/L4_state.json", state.toString(2))
            Receipts.repliedByRuri(root, lastCmd.optString("id"))
            Receipts.readByRuri(root, lastCmd.optString("id"))
        }
        val badge = JSONObject().put("ts", saved.optLong("ts"))
            .put("route", routeName).put("memory", "полная память")
        val existing = FileManager.read(root, "memory/route_badges.jsonl")
            .lineSequence().filter { it.isNotBlank() }.toMutableList()
        existing.add(badge.toString())
        FileManager.write(root, "memory/route_badges.jsonl", existing.takeLast(5000).joinToString("\n", postfix = "\n"))
        return savedId
    }

    private fun extractSourceUrls(raw: String): JSONArray {
        val found = linkedSetOf<String>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        if (key.equals("url", true) && child is String &&
                            (child.startsWith("https://") || child.startsWith("http://"))) found.add(child)
                        else walk(child)
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        runCatching { walk(JSONObject(raw)) }.onFailure { runCatching { walk(JSONArray(raw)) } }
        return JSONArray().also { out -> found.take(20).forEach { url -> out.put(url) } }
    }

    private fun readJson(root: File, path: String): JSONObject =
        try { JSONObject(FileManager.read(root, path)) } catch (_: Exception) { JSONObject() }
}
