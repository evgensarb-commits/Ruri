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
    private val responseLock = Any()

    internal class ModelRequestFailure(
        message: String,
        val retryAfterMs: Long = 0L,
        val failureKind: String = "provider"
    ) : IllegalStateException(message)

    fun respond(root: File, commanderMessageId: String? = null): String = synchronized(responseLock) {
        lastFinalMessageId = null
        var queueHead = CommanderReplyQueue.firstPending(root)
        while (queueHead != null) {
            val headId = queueHead.optString("message_id")
            if (Tombstone.isDeleted(root, headId)) {
                CommanderReplyQueue.markDeleted(root, headId)
                queueHead = CommanderReplyQueue.firstPending(root)
                continue
            }
            val saved = findSavedReply(root, headId)
            if (saved != null) {
                CommanderReplyQueue.markAnswered(root, headId, saved.first)
                updateLastSeenCommander(root, headId)
                OfflineQueue.clear(root)
                queueHead = CommanderReplyQueue.firstPending(root)
                continue
            }
            break
        }
        val requestedId = commanderMessageId?.takeIf { it.isNotBlank() }
        if (queueHead != null) {
            val headId = queueHead.optString("message_id")
            if (requestedId != null && requestedId != headId) {
                val delay = CommanderReplyQueue.nextDelayMs(root) ?: 1_000L
                throw ModelRequestFailure(
                    "Перед этим сообщением в очереди есть более раннее сообщение Командира",
                    delay.coerceAtLeast(1_000L),
                    "queued"
                )
            }
            val delay = queueHead.optLong("next_attempt_at", 0L) - System.currentTimeMillis()
            if (delay > 0L) {
                throw ModelRequestFailure(
                    "Предыдущее сообщение ожидает разрешённого времени повторной попытки",
                    delay,
                    queueHead.optString("last_error_kind", "queued")
                )
            }
        }
        val targetId = queueHead?.optString("message_id")?.takeIf { it.isNotBlank() }
            ?: requestedId ?: latestCommanderMessageId(root)
        if (!targetId.isNullOrBlank()) {
            findSavedReply(root, targetId)?.let { (id, text) ->
                lastFinalMessageId = id
                CommanderReplyQueue.markAnswered(root, targetId, id)
                updateLastSeenCommander(root, targetId)
                OfflineQueue.clear(root)
                return@synchronized text
            }
            CommanderReplyQueue.recordAttempt(root, targetId)
        }
        setTurnInFlight(root, true)
        try {
            respondInternal(root, allowRecovery = true, commanderMessageId = targetId)
        } catch (e: Exception) {
            if (!targetId.isNullOrBlank()) {
                val retryAfter = (e as? ModelRequestFailure)?.retryAfterMs ?: 0L
                val kind = (e as? ModelRequestFailure)?.failureKind ?: "unexpected"
                CommanderReplyQueue.recordFailure(root, targetId, kind, retryAfter)
                OfflineQueue.markPending(root, targetId)
            }
            throw e
        } finally {
            setTurnInFlight(root, false)
        }
    }

    private fun latestCommanderMessageId(root: File): String? =
        FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 1000)
            .asReversed().asSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .firstOrNull { it.optString("type") == "message" && it.optString("who") == "commander" &&
                !Tombstone.isDeleted(root, it.optString("id")) }
            ?.optString("id")?.takeIf { it.isNotBlank() }

    private fun findSavedReply(root: File, commanderMessageId: String): Pair<String, String>? =
        FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 2000)
            .asReversed().asSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .firstOrNull { it.optString("type") == "message" && it.optString("who") == "ruri" &&
                it.optString("reply_to") == commanderMessageId }
            ?.let { it.optString("id") to it.optString("text") }

    private fun updateLastSeenCommander(root: File, messageId: String) {
        val commander = FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 5000)
            .asReversed().asSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .firstOrNull {
                it.optString("id") == messageId && it.optString("type") == "message" &&
                    it.optString("who") == "commander"
            } ?: return
        val commanderTs = commander.optLong("ts", commander.optLong("timestamp", System.currentTimeMillis()))
        FileManager.updateL4State(root) { state ->
            state.put("last_seen_commander", maxOf(state.optLong("last_seen_commander", 0L), commanderTs))
        }
    }

    internal fun isCommanderMessage(root: File, messageId: String): Boolean {
        if (messageId.isBlank()) return false
        return FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 5000)
            .any { line ->
                runCatching { JSONObject(line) }.getOrNull()?.let { event ->
                    event.optString("id") == messageId &&
                        event.optString("type") == "message" &&
                        event.optString("who") == "commander"
                } == true
            }
    }

    internal fun setTurnInFlight(root: File, value: Boolean) {
        FileManager.updateL4State(root) { it.put("turn_in_flight", value) }
    }

    internal fun lastSavedReplyId(): String? = lastFinalMessageId

    internal fun shouldIncludeInChatHistory(root: File, event: JSONObject): Boolean =
        event.optString("type") == "message" && !Tombstone.isDeleted(root, event.optString("id"))

    private fun respondInternal(
        root: File,
        allowRecovery: Boolean,
        assumeReachable: Boolean = false,
        commanderMessageId: String? = null
    ): String {
        val config = JSONObject(SecretStore.readRuntimeConfig(root))
        val models = config.optJSONArray("models") ?: JSONArray()
        val current = config.optInt("current", -1)
        if (!NetState.online && !assumeReachable) {
            val reachableOrder = RouteSelector.orderedIndices(
                root, models, current, Task.CHAT, online = true, vpn = NetState.vpn
            )
            if (reachableOrder.isNotEmpty()) return respondInternal(root, allowRecovery = false, assumeReachable = true, commanderMessageId = commanderMessageId)
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
            throw ModelRequestFailure(reason, 60_000L, "no_route")
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
        val outcomeDigest = OutcomeDigest.build(root)
        OutcomeDigest.clearPresentedCorrection(root)
        val system = "Имя: ${character.optString("name")}; обозначение: ${character.optString("designation")}. " +
            "Личность: ${character.optString("persona")}. " +
            "Непрерывность: ${character.optString("continuity_rules")}. " +
            "Правила речи: ${character.optString("speech_rules")}. " +
            "Длина ответа: ${character.optString("length_policy")}. " +
            "Эмоции: ${character.optString("emotions")}. " +
            "Регуляторы характера: ${character.optJSONArray("regulators") ?: JSONArray()}. " +
            "Примеры разнообразного входа (варианты, не шаблоны): ${character.optJSONArray("opening_exemplars") ?: JSONArray()}. " +
            StyleGuard.repetitiveOpeningNote(root) + " " +
            "Фразы, которые нельзя использовать: ${bannedPhrases}. " +
            Capabilities.bodySection(root) + " " +
            EmotionEngine.promptSection(root) + " " +
            outcomeDigest + " " +
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
        val newestFirstHistory = lines.asReversed().mapNotNull { line ->
            try {
                val event = JSONObject(line)
                if (!shouldIncludeInChatHistory(root, event)) return@mapNotNull null
                val text = event.optString("text").trim()
                if (text.isBlank()) return@mapNotNull null
                val role = if (event.optString("who") == "ruri") "assistant" else "user"
                JSONObject().put("role", role).put("content", text)
                    .put("_ruri_event_id", event.optString("id"))
            } catch (_: Exception) { null }
        }
        val targetPosition = if (commanderMessageId.isNullOrBlank()) 0
            else newestFirstHistory.indexOfFirst { it.optString("_ruri_event_id") == commanderMessageId }
        if (!commanderMessageId.isNullOrBlank() && targetPosition < 0) {
            throw IllegalStateException("Сообщение Командира не найдено в L0; оно осталось в очереди")
        }
        val history = (if (targetPosition >= 0) newestFirstHistory.drop(targetPosition) else newestFirstHistory)
            .take(14).reversed()
        if (history.isEmpty() || history.last().optString("role") != "user") {
            throw IllegalStateException("Не найдено последнее сообщение командира")
        }
        val commanderText = history.last().optString("content")
        EmotionEngine.onCommanderMessage(root, commanderText, history.last().optString("_ruri_event_id"))
        val task = RouteSelector.detectTask(commanderText)
        val adult = RouteSelector.isAdultTopic(commanderText) || RouteSelector.nsfwWindow(root)
        fun selectOrder(online: Boolean): List<Int> = RouteSelector.orderedIndices(root, models, current, task, online, NetState.vpn, adult)
        var order = selectOrder(NetState.online || assumeReachable)
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
        val assistantHistoryCount = history.count { it.optString("role") == "assistant" }
        var assistantHistoryIndex = 0
        history.forEach { item ->
            val providerMessage = JSONObject(item.toString())
            providerMessage.remove("_ruri_event_id")
            if (providerMessage.optString("role") == "assistant") {
                val content = providerMessage.optString("content")
                providerMessage.put("content", StyleGuard.stripOlderOpening(
                    "ruri", assistantHistoryIndex, assistantHistoryCount, content
                ))
                assistantHistoryIndex++
            }
            messages.put(providerMessage)
        }
        if (!hasFullMemoryContext(messages)) {
            OfflineQueue.markPending(root)
            throw IllegalStateException("Полная память не собрана; запрос к модели не отправлен")
        }

        var lastError = "Все настроенные модели недоступны"
        var retryAfterMs = 0L
        var lastFailureKind = "provider"
        var hadNetworkAttempt = false
        var allFailuresNetwork = true
        var followupRequests = 0
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
            val requestNext: (JSONArray) -> String? = { nextMessages ->
                if (followupRequests >= 12) {
                    throw ModelRequestFailure(
                        "Достигнут безопасный лимит 12 дополнительных запросов к модели за один ответ; сообщение останется в очереди",
                        60_000L,
                        "followup_budget"
                    )
                }
                followupRequests++
                requestToolFollowup(model, nextMessages, root, routeId, task)
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
                    .put("temperature", 0.9)
                    .put("presence_penalty", 0.6)
                    .put("frequency_penalty", 0.4)
                    .put("seed", (System.currentTimeMillis() % 100000L).toInt())
                stage = "WRITE"
                diagnostic.put("stage", stage)
                val writeStart = SystemClock.elapsedRealtime()
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                diagnostic.put("write_ms", SystemClock.elapsedRealtime() - writeStart)
                stage = "HTTP_RESPONSE"
                diagnostic.put("stage", stage)
                val responseStart = SystemClock.elapsedRealtime()
                val code = connection.responseCode
                RouteMeta.recordHttpStatus(root, routeId, code)
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
                    if (code == 429) {
                        val parsedRetryAfter = parseRetryAfterMillis(connection.getHeaderField("Retry-After"))
                        retryAfterMs = maxOf(retryAfterMs, parsedRetryAfter.takeIf { it > 0L } ?: 60_000L)
                        lastFailureKind = "rate_limit"
                    } else if (code in 500..599) {
                        retryAfterMs = maxOf(retryAfterMs, parseRetryAfterMillis(connection.getHeaderField("Retry-After")))
                        lastFailureKind = "http_$code"
                    } else {
                        lastFailureKind = "http_$code"
                    }
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
                    runCatching { RouteMeta.recordFailure(root, routeId, "empty_response", task) }
                    diagnostic.put("result", "empty_reply")
                    NetworkDiagnostics.record(root, diagnostic)
                    lastError = "Модель вернула пустой ответ"
                    allFailuresNetwork = false
                    continue
                }
                val meta = JSONObject()
                lastSearchSourceUrls = JSONArray()
                val initialDiaryText = ToolLoop.diaryActionText(initialReply)
                val rawReply = if (initialDiaryText != null) "" else
                    ToolLoop.unwrapDiaryReply(root, initialReply, messages, meta) { nextMessages ->
                        requestNext(nextMessages)
                    }
                val (replyWithoutDecision, _) = EmotionEngine.parseInitiativeDecision(rawReply)
                val (cleanedReply, emotionDelta) = EmotionEngine.parseAndStrip(replyWithoutDecision)
                val emotion = if (emotionDelta != null) EmotionEngine.applyDelta(root, emotionDelta, "chat") else null
                var finalReply = cleanedReply
                var guard = 0
                while (guard < 12) {
                    val (clean, diaryTexts, toolActions) = ToolLoop.stripActions(finalReply)
                    diaryTexts.forEach { routeChatDiaryAction(root, it, emotion) }
                    if (toolActions.isEmpty()) { finalReply = clean; break }
                    toolActions.forEach { action ->
                        val name = action.optString("action")
                        val toolResult = executeTool(root, name, action)
                        FileManager.appendEvent(root, SecretRedactor.redactEvent(JSONObject().put("type", "tool_call").put("action", name).put("params", action)))
                        FileManager.appendEvent(root, SecretRedactor.redactEvent(JSONObject().put("type", "tool_result").put("action", name).put("ok", toolResult.first).put("text", toolResult.second.take(4000))))
                        messages.put(JSONObject().put("role", "user").put("content", "[ИНСТРУМЕНТ $name]: ${toolResult.second.take(if (name == "read_shelf") 7600 else 4000)}"))
                    }
                    finalReply = requestNext(messages) ?: clean
                    guard++
                }
                val (cleanFinal, tailDiary, _) = ToolLoop.stripActions(finalReply)
                tailDiary.forEach { routeChatDiaryAction(root, it, emotion) }
                if (initialDiaryText != null) routeChatDiaryAction(root, initialDiaryText, emotion)
                var proseReply = if (initialDiaryText == null) ToolLoop.proseCandidate(cleanFinal) else null
                if (proseReply.isNullOrBlank() && initialDiaryText == null && tailDiary.isEmpty()) {
                    messages.put(JSONObject().put("role", "system")
                        .put("content", "Предыдущий ответ был действительно пустым. Ответь обычной прозой без JSON или верни write_diary с непустым text."))
                    val retry = requestNext(messages).orEmpty()
                    val (retryClean, retryDiary, _) = ToolLoop.stripActions(retry)
                    retryDiary.forEach { routeChatDiaryAction(root, it, emotion) }
                    proseReply = ToolLoop.proseCandidate(retryClean)
                }
                val diaryFallback = initialDiaryText ?: tailDiary.firstOrNull()
                    ?: ToolLoop.diaryActionText(finalReply).orEmpty()
                val reply = resolveChatReply(cleanFinal, diaryFallback)
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
                OutcomeDigest.verifyClaims(root, reply)
                // Keep Routeway as primary; fallback success is temporary, not a reconfiguration.
                appendFinalReply(
                    root, reply, emotion, meta,
                    routeId, commanderMessageId
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
                val failureKind = (e as? ModelRequestFailure)?.failureKind
                    ?: RouteSelector.classify(null, e)
                if (e is ModelRequestFailure) {
                    retryAfterMs = maxOf(retryAfterMs, e.retryAfterMs)
                    lastFailureKind = e.failureKind
                } else {
                    runCatching { RouteMeta.recordFailure(root, routeId, failureKind, task) }
                }
                if (!RouteRecovery.isNetworkFailure(failureKind)) allFailuresNetwork = false
                lastError = RouteRecovery.describeException(model.optString("name", model.optString("id")), stage, e, failureKind)
                diagnostic.put("failure_message", lastError)
                NetworkDiagnostics.record(root, diagnostic)
                if (failureKind == "followup_budget") break
                if (e is SocketTimeoutException) retryableModels.add(index)
                else retryableModels.remove(index)
                android.util.Log.w("Ruri", "Chat model failed at $stage: ${e.javaClass.simpleName}")
            } finally {
                connection?.disconnect()
            }
        }
        if (hadNetworkAttempt && allFailuresNetwork && allowRecovery && Probe.claimRecoveryRefresh()) {
            Probe.refreshReachability(root, models, topOnly = false, force = true)
            return respondInternal(root, allowRecovery = false, assumeReachable = true, commanderMessageId = commanderMessageId)
        }
        OfflineQueue.markPending(root)
        throw ModelRequestFailure(lastError, retryAfterMs, lastFailureKind)
    }

    internal fun parseRetryAfterMillis(value: String?, now: Long = System.currentTimeMillis()): Long {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return 0L
        raw.toLongOrNull()?.let { return (it.coerceAtLeast(0L) * 1000L).coerceAtMost(24L * 60L * 60L * 1000L) }
        return runCatching {
            val date = java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
            (date - now).coerceAtLeast(0L).coerceAtMost(24L * 60L * 60L * 1000L)
        }.getOrDefault(0L)
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

    internal fun routeChatDiaryAction(root: File, rawText: String, emotion: JSONObject? = null) {
        val safe = SecretRedactor.redactText(rawText.trim())
        if (safe.isNotBlank()) Diary.append(root, safe, "chat_write_diary", emotion, "thought")
    }

    internal fun resolveChatReply(cleanReply: String, diaryFallback: String): String {
        return ToolLoop.proseCandidate(cleanReply)?.takeIf { it.isNotBlank() }
            ?: SecretRedactor.redactText(diaryFallback.trim()).takeIf { it.isNotBlank() }
            ?: "(не смогла сформулировать ответ)"
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
            "read_shelf" -> setOf("action", "name", "offset")
            "remember_fact" -> setOf("action", "text")
            "self_check" -> setOf("action")
            else -> return null
        }
        val keys = action.keys()
        while (keys.hasNext()) if (keys.next() !in allowed) return null
        when (name) {
            "search", "recall" -> if (action.optString("query").isBlank() || action.optString("query").length > 300) return null
            "read_shelf" -> {
                if (ShelfWatcher.safeName(action.optString("name")) == null) return null
                if (action.has("offset") && action.optInt("offset", -1) < 0) return null
            }
            "read_url" -> {
                val url = action.optString("url").trim()
                if (url.length > 2048 || !url.matches(Regex("(?i)^https?://.+"))) return null
            }
            "remember_fact" -> if (action.optString("text").trim().length !in 5..300) return null
            "self_check" -> Unit
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
                "list_shelf" -> true to SecretRedactor.redactText(shelfListing(root))
                "read_shelf" -> {
                    val safe = ShelfWatcher.safeName(params.optString("name"))
                        ?: return false to "Недопустимое имя файла"
                    true to SecretRedactor.redactText(shelfText(root, safe, params.optInt("offset", 0)))
                }
                "recall" -> true to SecretRedactor.redactText(MemoryRetriever.recall(root, params.optString("query"), 5))
                "remember_fact" -> true to SecretRedactor.redactText(rememberFact(root, params.optString("text")))
                "self_check" -> true to OutcomeDigest.build(root)
                else -> false to "Неизвестное действие"
            }
        } catch (e: Exception) {
            false to SecretRedactor.redactText(e.message ?: "Ошибка инструмента")
        }
    }

    internal fun shelfText(root: File, name: String, offset: Int = 0): String {
        val safe = ShelfWatcher.safeName(name) ?: return "Недопустимое имя файла"
        val file = File(root, "shelf/" + safe)
        if (!file.isFile) return "Файл не найден"
        if (file.length() > 8L * 1024L * 1024L) return "Файл превышает безопасный предел чтения 8 МиБ"
        val bytes = runCatching { file.readBytes() }.getOrElse { return "Файл недоступен" }
        val mime = Vision.mimeOf(bytes)
        val lower = safe.lowercase(java.util.Locale.ROOT)
        val extracted = when {
            mime != null -> FileManager.read(root, "shelf/.desc/" + safe + ".txt")
                .takeIf { it.isNotBlank() } ?: return "[изображение без описания: vision недоступен]"
            lower.endsWith(".docx") || lower.endsWith(".odt") -> ShelfWatcher.extractOffice(bytes, lower)
                .ifBlank { return "[не удалось извлечь текст из офисного документа]" }
            else -> ShelfWatcher.decodeText(bytes)
        }
        val text = SecretRedactor.redactText(extracted)
        if (mime != null && offset == 0 && text.length <= 7000) return text
        if (offset > text.length) return "Смещение $offset за пределами текста (${text.length} символов)"
        if (offset == text.length) return "Документ прочитан полностью: $offset/${text.length} символов."
        val end = minOf(text.length, offset + 7000)
        val part = text.substring(offset, end)
        val partNumber = offset / 7000 + 1
        val totalParts = (text.length + 6999) / 7000
        return buildString {
            append("Файл: ").append(safe).append("\n")
            append("Часть ").append(partNumber).append(" из ").append(totalParts)
                .append("; символы ").append(offset).append("–").append(end)
                .append(" из ").append(text.length).append(".\n")
            append(part)
            if (end < text.length) append("\n[ПРОДОЛЖЕНИЕ ОБЯЗАТЕЛЬНО: вызови read_shelf с тем же name и offset=")
                .append(end).append("; не делай выводов о документе до последней части.]")
            else append("\n[КОНЕЦ ФАЙЛА: прочитан весь извлечённый текст.]")
        }
    }

    private fun shelfMagic(file: File): ByteArray = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(16)
            val count = input.read(header)
            if (count <= 0) byteArrayOf() else header.copyOf(count)
        }
    }.getOrDefault(byteArrayOf())

    private fun shelfListing(root: File): String {
        val files = File(root, "shelf").listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }.orEmpty()
        if (files.isEmpty()) return "Полка пуста."
        return files.joinToString("\n") { file ->
            val lower = file.name.lowercase()
            val kind = when {
                lower.endsWith(".docx") || lower.endsWith(".odt") -> "office"
                lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv") -> "text"
                Vision.mimeOf(shelfMagic(file)) != null -> "image"
                else -> "file"
            }
            "${file.name} · ${file.length()} байт · $kind"
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

    internal fun requestToolFollowup(model: JSONObject, messages: JSONArray, root: File? = null, routeId: String = "", task: Task? = null): String? {
        val base = model.optString("base_url").trimEnd('/')
        if (!base.startsWith("https://", ignoreCase = true)) {
            throw ModelRequestFailure("Provider endpoint must use HTTPS", 0L, "invalid_endpoint")
        }
        val safeMessages = providerMessagesWithFullMemory(messages)
            ?: throw ModelRequestFailure("Не удалось собрать полный контекст памяти для продолжения ответа", 0L, "context_error")
        var connection: HttpURLConnection? = null
        try {
            if (root != null && routeId.isNotBlank()) RouteMeta.recordAttempt(root, routeId)
            connection = (URL(base + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 18000
                readTimeout = 35000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                val key = model.optString("api_key")
                if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer " + key)
            }
            val body = JSONObject().put("model", model.optString("model"))
                .put("messages", safeMessages)
                .put("temperature", 0.9)
                .put("presence_penalty", 0.6)
                .put("frequency_penalty", 0.4)
                .put("seed", (System.currentTimeMillis() % 100000L).toInt())
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (root != null && routeId.isNotBlank()) RouteMeta.recordHttpStatus(root, routeId, code)
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()
            val usage = response.optJSONObject("usage")
            val tokens = usage?.optLong("total_tokens", -1L)?.takeIf { it >= 0L }
                ?: ((usage?.optLong("prompt_tokens", 0L) ?: 0L) + (usage?.optLong("completion_tokens", 0L) ?: 0L))
            if (root != null && routeId.isNotBlank() && tokens > 0) RouteMeta.addTokens(root, routeId, tokens)
            if (code !in 200..299) {
                val kind = RouteSelector.classify(code, null, raw)
                if (root != null && routeId.isNotBlank()) RouteMeta.recordFailure(root, routeId, kind, task)
                val retryAfter = if (code == 429) {
                    parseRetryAfterMillis(connection.getHeaderField("Retry-After")).takeIf { it > 0L } ?: 60_000L
                } else parseRetryAfterMillis(connection.getHeaderField("Retry-After"))
                throw ModelRequestFailure("HTTP $code при продолжении ответа", retryAfter, if (code == 429) "rate_limit" else kind)
            }
            val content = response.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")?.trim().orEmpty()
            if (content.isBlank()) {
                if (root != null && routeId.isNotBlank()) RouteMeta.recordFailure(root, routeId, "empty_response", task)
                throw ModelRequestFailure("Провайдер вернул пустое продолжение ответа", 0L, "empty_response")
            }
            if (root != null && routeId.isNotBlank()) RouteMeta.recordSuccess(root, routeId, task)
            return content
        } catch (e: ModelRequestFailure) {
            throw e
        } catch (e: Exception) {
            if (root != null && routeId.isNotBlank()) {
                val kind = if (e is java.net.SocketTimeoutException) "timeout" else "network"
                RouteMeta.recordFailure(root, routeId, kind, task)
            }
            val kind = if (e is java.net.SocketTimeoutException) "timeout" else "network"
            throw ModelRequestFailure(e.message ?: "Ошибка сети при продолжении ответа", 0L, kind)
        } finally {
            connection?.disconnect()
        }
    }

    internal fun appendFinalReply(
        root: File,
        reply: String,
        emotion: JSONObject? = null,
        meta: JSONObject? = null,
        routeName: String = "маршрут",
        commanderMessageId: String? = null
    ): String {
        val sourceUrls = meta?.optJSONArray("source_urls") ?: JSONArray()
        val searchOk = meta?.takeIf { it.has("search_ok") }?.optBoolean("search_ok")
        return appendFinalReplyInternal(root, reply, routeName, sourceUrls, emotion, searchOk,
            includeEmptySources = meta?.has("source_urls") == true,
            commanderMessageId = commanderMessageId)
    }

    internal fun appendFinalReply(
        root: File,
        reply: String,
        routeName: String,
        sourceUrls: JSONArray = JSONArray(),
        emotion: JSONObject? = null,
        commanderMessageId: String? = null
    ): String = appendFinalReplyInternal(root, reply, routeName, sourceUrls, emotion, null,
        commanderMessageId = commanderMessageId)

    private fun appendFinalReplyInternal(
        root: File,
        reply: String,
        routeName: String,
        sourceUrls: JSONArray,
        emotion: JSONObject?,
        searchOk: Boolean?,
        includeEmptySources: Boolean = false,
        commanderMessageId: String? = null
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
        if (!commanderMessageId.isNullOrBlank()) event.put("reply_to", commanderMessageId)
        val saved = JSONObject(FileManager.appendEvent(root, event))
        val savedId = saved.optString("id").ifBlank { id }
        Receipts.delivered(root, savedId)
        lastFinalMessageId = savedId
        val lastCmd = if (!commanderMessageId.isNullOrBlank()) {
            FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 2000)
                .asReversed().asSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .firstOrNull { it.optString("type") == "message" && it.optString("who") == "commander" &&
                    it.optString("id") == commanderMessageId && !Tombstone.isDeleted(root, commanderMessageId) }
        } else {
            FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 500)
                .asReversed().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .firstOrNull { it.optString("type") == "message" && it.optString("who") == "commander" &&
                    !Tombstone.isDeleted(root, it.optString("id")) }
        }
        if (lastCmd != null) {
            val commanderTs = lastCmd.optLong("ts", lastCmd.optLong("timestamp", System.currentTimeMillis()))
            FileManager.updateL4State(root) { state ->
                state.put("last_seen_commander", maxOf(state.optLong("last_seen_commander", 0L), commanderTs))
            }
            val commanderId = lastCmd.optString("id")
            Receipts.repliedByRuri(root, commanderId)
            Receipts.readByRuri(root, commanderId)
            CommanderReplyQueue.markAnswered(root, commanderId, savedId)
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
