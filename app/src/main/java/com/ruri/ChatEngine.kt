package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Native model gateway. The WebView never sends provider requests directly, and there is
 * exactly one provider: Routeway's Gemma MeroMero (see [Route]).
 *
 * A turn makes a single attempt. When it fails, the delay until the next attempt is
 * persisted by [CommanderReplyQueue] — ten seconds for transient failures, no automatic
 * retry at all for terminal ones, which wait for the «ПОВТОРИТЬ» button in the chat.
 */
object ChatEngine {
    const val OFFLINE_REPLY = "Связи нет. Отвечу, когда она вернётся."
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
            val kind = queueHead.optString("last_error_kind", "queued")
            if (queueHead.optBoolean("manual_retry", false)) {
                throw ModelRequestFailure(
                    RetryPolicy.explain(kind),
                    0L,
                    kind.ifBlank { "queued" }
                )
            }
            val delay = queueHead.optLong("next_attempt_at", 0L) - System.currentTimeMillis()
            if (delay > 0L) {
                throw ModelRequestFailure(
                    "Повторная попытка запланирована через ${(delay / 1000L).coerceAtLeast(1L)} с",
                    delay,
                    kind.ifBlank { "queued" }
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
            return@synchronized respondInternal(root, targetId)
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

    // ---------------------------------------------------------------- one turn

    private fun respondInternal(root: File, commanderMessageId: String?): String {
        val apiKey = Route.apiKey(root)
        if (apiKey.isBlank()) {
            val detail = "ключ Routeway не задан"
            recordDiagnostic(root, "no_key", null, detail, JSONArray())
            OfflineQueue.markPending(root)
            throw ModelRequestFailure(RetryPolicy.explain("no_key"), 0L, "no_key")
        }
        if (!NetState.online) {
            val detail = "Android сообщает об отсутствии сети"
            recordDiagnostic(root, "offline", null, detail, JSONArray())
            OfflineQueue.markPending(root)
            throw ModelRequestFailure(RetryPolicy.explain("offline"), 0L, "offline")
        }

        val messages = buildMessages(root, commanderMessageId)
        if (!hasFullMemoryContext(messages)) {
            OfflineQueue.markPending(root)
            throw ModelRequestFailure(
                "Полная память не собрана: в контексте нет обязательных слоёв L0–L3. Запрос к модели не отправлен.",
                0L,
                "config"
            )
        }
        val safeMessages = providerMessagesWithFullMemory(messages) ?: messages

        val result = Transport.chat(safeMessages, apiKey, 400)
        when (result) {
            is Transport.Result.Success -> {
                recordDiagnostic(root, "ok", result.httpStatus, "", result.stages)
                runCatching { RouteMeta.recordAttempt(root, Route.ID) }
                runCatching { RouteMeta.recordHttpStatus(root, Route.ID, result.httpStatus) }
                return finishTurn(root, result.reply, commanderMessageId)
            }

            is Transport.Result.Failure -> {
                var kind = result.kind
                var detail = result.detail
                // MeroMero is only reachable through a VPN. A network-level failure while the
                // tunnel is down is really "no VPN", which is terminal and actionable.
                if (Route.REQUIRES_VPN && !NetState.vpn &&
                    (kind == "network" || kind == "timeout" || kind == "server" || kind == "bad_response")
                ) {
                    kind = "no_vpn"
                    detail = detail.ifBlank { "VPN-туннель не обнаружен" }
                }
                recordDiagnostic(root, kind, result.httpStatus, detail, result.stages)
                runCatching { RouteMeta.recordAttempt(root, Route.ID) }
                if (result.httpStatus != null) {
                    runCatching { RouteMeta.recordHttpStatus(root, Route.ID, result.httpStatus!!) }
                }
                runCatching { RouteMeta.recordFailure(root, Route.ID, kind, Task.CHAT) }
                OfflineQueue.markPending(root)
                throw ModelRequestFailure(
                    RetryPolicy.explain(kind, result.httpStatus, detail),
                    if (RetryPolicy.isRetryable(kind)) RetryPolicy.AUTO_RETRY_DELAY_MS else 0L,
                    kind
                )
            }
        }
    }

    /** Cleans the raw provider answer and stores it as Ruri's chat message. */
    private fun finishTurn(root: File, rawReply: String, commanderMessageId: String?): String {
        val (replyWithoutDecision, _) = EmotionEngine.parseInitiativeDecision(rawReply)
        val (withoutEmotion, emotionDelta) = EmotionEngine.parseAndStrip(replyWithoutDecision)
        val emotion = if (emotionDelta != null) EmotionEngine.applyDelta(root, emotionDelta, "chat") else null

        val (cleanReply, diaryTexts, _) = ToolLoop.stripActions(withoutEmotion)
        diaryTexts.forEach { routeChatDiaryAction(root, it, emotion) }

        val reply = resolveChatReply(cleanReply, diaryTexts.firstOrNull().orEmpty())
        if (recordAdultRefusal(
                root = root,
                routeId = Route.ID,
                task = Task.CHAT,
                commanderText = currentCommanderText(root),
                reply = reply,
                diagnostic = JSONObject()
            )
        ) {
            throw ModelRequestFailure(RetryPolicy.explain("refusal"), 0L, "refusal")
        }

        runCatching { RouteMeta.recordSuccess(root, Route.ID, Task.CHAT) }
        runCatching { OutcomeDigest.verifyClaims(root, reply) }
        val savedId = appendFinalReply(root, reply, emotion, null, Route.DISPLAY_NAME, commanderMessageId)
        runCatching { BackupPolicy.checkpointState(root) }
        runCatching { OfflineQueue.clear(root) }
        lastFinalMessageId = savedId
        return reply
    }

    private fun currentCommanderText(root: File): String =
        FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 1000)
            .asReversed().asSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .firstOrNull { it.optString("type") == "message" && it.optString("who") == "commander" }
            ?.optString("text").orEmpty()

    // ---------------------------------------------------------------- context

    /**
     * Builds the provider context: identity, emotions and the four memory layers.
     *
     * No tool protocol is advertised here on purpose: the app has no search, shelf or vision
     * executor any more, so telling the model about JSON actions would only make it emit
     * actions nobody runs.
     */
    internal fun buildMessages(root: File, commanderMessageId: String?): JSONArray {
        val character = readJson(root, "core/character.json")
        val priorities = readJson(root, "core/priorities.json")
        val constitution = readJson(root, "core/constitution.json")
        val state = readJson(root, "memory/L4_state.json")
        val selfModel = readJson(root, "memory/self_model.json")
        val goals = readJson(root, "memory/goals.json")
        val facts = readJson(root, "memory/L1_facts.json").optJSONArray("facts") ?: JSONArray()
        val diary = readJson(root, "memory/L2_diary.json").optJSONArray("entries") ?: JSONArray()
        val summary = readJson(root, "memory/L3_summary.json").optString("summary").take(4000)

        val factContext = StringBuilder()
        for (index in maxOf(0, facts.length() - 8) until facts.length()) {
            val fact = facts.optJSONObject(index) ?: continue
            val value = fact.optString("text").trim()
            if (value.isNotBlank()) factContext.append("- ").append(value.take(200)).append("\n")
        }
        val diaryContext = StringBuilder()
        for (index in maxOf(0, diary.length() - 3) until diary.length()) {
            val entry = diary.optJSONObject(index) ?: continue
            if (entry.optString("id").isNotBlank() && Tombstone.isDeleted(root, entry.optString("id"))) continue
            val value = entry.optString("text").trim()
            if (value.isNotBlank()) diaryContext.append("- ").append(value.take(300)).append("\n")
        }
        val bodyEventContext = FileManager.readLastLines(
            root, "memory/L0_raw.jsonl", maxLines = 1200, maxBytes = 1024 * 1024
        ).asReversed().mapNotNull { line ->
            runCatching {
                val event = JSONObject(line)
                val type = event.optString("type")
                if (type.isBlank() || type == "message" || type == "tool_call" || type == "tool_result") null
                else "- $type: " + (event.optString("text").ifBlank { event.optString("reason") }).take(260)
            }.getOrNull()
        }.take(6).asReversed().joinToString("\n")
        val bannedPhrases = character.optJSONArray("banned_phrases") ?: JSONArray()

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
            "Не выдумывай факты, отделяй воспоминания от предположений. " +
            "Приоритеты: ${priorities.optString("priority_1", "Жить")}; ${priorities.optString("priority_2", "Любить Командира")}. " +
            "Конституция: ${constitution.optJSONArray("primary_needs") ?: JSONArray()}. " +
            "Дополнительные черты: ${character.optJSONObject("traits") ?: JSONObject()}. " +
            "Состояние: ${state.optString("mood", "собранная")}, энергия ${state.optInt("energy", 5)}, фокус: " +
            "${state.optString("current_focus", selfModel.optString("current_focus"))}. " +
            "Активные цели: ${goals.optJSONArray("active") ?: JSONArray()}. " +
            "Незавершённые задачи: ${state.optJSONArray("active_tasks") ?: JSONArray()}. " +
            ChatStyle.architectureSection() +
            ChatStyle.chatDirective(root) +
            "Ты общаешься с Командиром в чате приложения. Отвечай обычной прозой, без JSON и без служебных блоков. " +
            "Ниже — ТВОЯ ДОЛГОСРОЧНАЯ ПАМЯТЬ. Это не контекст одного диалога; ты помнишь между сессиями.\n" +
            "ТВОЯ АВТОБИОГРАФИЯ (L3):\n$summary\n" +
            "ТВОИ ФАКТЫ (L1):\n$factContext\n" +
            "ТВОЙ ДНЕВНИК (L2):\n$diaryContext\n" +
            "ПОСЛЕДНИЕ СОБЫТИЯ ТВОЕГО ТЕЛА (L0):\n$bodyEventContext"

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
            .take(6).reversed()
        if (history.isEmpty() || history.last().optString("role") != "user") {
            throw IllegalStateException("Не найдено последнее сообщение командира")
        }
        EmotionEngine.onCommanderMessage(root, history.last().optString("content"),
            history.last().optString("_ruri_event_id"))
        // Associative recall is local and free: it runs on the device and only adds context.
        // Capabilities promises it, so the chat prompt must actually carry it.
        val recalled = runCatching {
            MemoryRetriever.recall(root, history.last().optString("content"), limit = 3)
        }.getOrDefault("")
        if (recalled.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content",
                "Ассоциативно найденные воспоминания. Используй их, если они относятся к вопросу. " +
                    "Не выдавай предположения за точные воспоминания; при необходимости обозначай неопределённость.\n" +
                    recalled))
        }
        val assistantHistoryCount = history.count { it.optString("role") == "assistant" }
        var assistantHistoryIndex = 0
        history.forEach { item ->
            val providerMessage = JSONObject(item.toString())
            providerMessage.remove("_ruri_event_id")
            if (providerMessage.optString("role") == "assistant") {
                providerMessage.put("content", StyleGuard.stripOlderOpening(
                    "ruri", assistantHistoryIndex, assistantHistoryCount, providerMessage.optString("content")
                ))
                assistantHistoryIndex++
            }
            messages.put(providerMessage)
        }
        return messages
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

    /**
     * Guard against sending a request without the long-term memory.
     *
     * It verifies the four memory layers and the presence of a real dialogue turn. It does
     * NOT look for tool keywords any more: tools are gone, so requiring a mention of them
     * would reject every well-formed request.
     */
    internal fun hasFullMemoryContext(messages: JSONArray): Boolean {
        if (messages.length() < 2) return false
        val system = messages.optJSONObject(0)?.optString("content").orEmpty()
        val hasMemoryLayers = listOf("L0", "L1", "L2", "L3").all(system::contains)
        val hasConversation = (1 until messages.length()).any {
            val role = messages.optJSONObject(it)?.optString("role")
            role == "user" || role == "assistant"
        }
        return hasMemoryLayers && hasConversation
    }

    // ---------------------------------------------------------------- diagnostics

    private fun recordDiagnostic(
        root: File,
        kind: String,
        httpStatus: Int?,
        detail: String,
        stages: JSONArray
    ) {
        runCatching {
            fun stage(name: String): JSONObject? {
                for (i in 0 until stages.length()) {
                    val item = stages.optJSONObject(i) ?: continue
                    if (item.optString("name") == name) return item
                }
                return null
            }
            val last = if (stages.length() > 0) stages.optJSONObject(stages.length() - 1) else null
            val entry = JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("provider", Route.DISPLAY_NAME)
                .put("model", Route.MODEL)
                .put("base_url", Route.BASE_URL)
                .put("vpn", NetState.vpn)
                .put("online", NetState.online)
                .put("result", kind)
                .put("error_kind", kind)
                .put("failure_message", SecretRedactor.redactText(detail).take(300))
                .put("explanation", RetryPolicy.explain(kind, httpStatus, detail))
                .put("stage", last?.optString("name").orEmpty())
                .put("http_status", httpStatus ?: JSONObject.NULL)
                .put("dns_ms", stage("DNS")?.opt("ms") ?: JSONObject.NULL)
                .put("connect_tls_ms", stage("CONNECT")?.opt("ms") ?: JSONObject.NULL)
                .put("write_ms", stage("WRITE")?.opt("ms") ?: JSONObject.NULL)
                .put("response_wait_ms", stage("HTTP")?.opt("ms") ?: JSONObject.NULL)
                .put("response_body_ms", stage("READ")?.opt("ms") ?: JSONObject.NULL)
                .put("stages", stages)
            NetworkDiagnostics.record(root, entry)
        }
    }

    // ---------------------------------------------------------------- queue helpers

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

    // ---------------------------------------------------------------- reply plumbing

    internal fun parseRetryAfterMillis(value: String?, now: Long = System.currentTimeMillis()): Long {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return 0L
        raw.toLongOrNull()?.let { seconds -> return (seconds * 1000L).coerceIn(0L, 24L * 60L * 60L * 1000L) }
        return runCatching {
            val parsed = java.time.ZonedDateTime.parse(
                raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            )
            val date = parsed.toInstant().toEpochMilli()
            (date - now).coerceAtLeast(0L).coerceAtMost(24L * 60L * 60L * 1000L)
        }.getOrDefault(0L)
    }

    internal fun routeChatDiaryAction(root: File, rawText: String, emotion: JSONObject? = null) {
        val safe = SecretRedactor.redactText(rawText.trim())
        if (safe.isNotBlank()) Diary.append(root, safe, "chat_write_diary", emotion, "thought")
    }

    /**
     * Detects a refusal on an adult topic, records a redacted snippet in L0 and reports it
     * so the caller does not publish the refusal as a normal answer.
     */
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
        FileManager.appendEvent(root, JSONObject()
            .put("type", "nsfw_refusal")
            .put("route", routeId)
            .put("snippet", SecretRedactor.redactText(reply).take(300))
            .put("ts", now))
        runCatching { RouteMeta.recordFailure(root, routeId, "refusal", task) }
        return true
    }

    internal fun resolveChatReply(cleanReply: String, diaryFallback: String): String {
        return ToolLoop.proseCandidate(cleanReply)?.takeIf { it.isNotBlank() }
            ?: SecretRedactor.redactText(diaryFallback.trim()).takeIf { it.isNotBlank() }
            ?: "(не смогла сформулировать ответ)"
    }

    /** Accepted action protocol. Only the tools that still have an executor are allowed. */
    internal fun parseAction(reply: String): JSONObject? {
        val trimmed = reply.trim()
        val fenced = Regex("(?is)^```(?:json)?\\s*(\\{.*\\})\\s*```$").find(trimmed)
        val jsonText = fenced?.groupValues?.get(1) ?: trimmed.takeIf { it.startsWith("{") && it.endsWith("}") }
            ?: return null
        val action = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val name = action.optString("action")
        val allowed = when (name) {
            "recall" -> setOf("action", "query")
            "remember_fact" -> setOf("action", "text")
            "self_check" -> setOf("action")
            else -> return null
        }
        val keys = action.keys()
        while (keys.hasNext()) if (keys.next() !in allowed) return null
        when (name) {
            "recall" -> {
                if (action.optString("query").isBlank() || action.optString("query").length > 300) return null
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

    internal fun executeTool(root: File, action: String, params: JSONObject): Pair<Boolean, String> {
        return try {
            when (action) {
                "recall" -> true to SecretRedactor.redactText(
                    MemoryRetriever.recall(root, params.optString("query"), 5)
                )
                "remember_fact" -> true to SecretRedactor.redactText(rememberFact(root, params.optString("text")))
                "self_check" -> true to OutcomeDigest.build(root)
                else -> false to "Неизвестное действие"
            }
        } catch (e: Exception) {
            false to SecretRedactor.redactText(e.message ?: "Ошибка инструмента")
        }
    }

    /**
     * Follow-up request used by the autonomous thinker. Kept on [Transport] so every request
     * the app makes is instrumented the same way.
     */
    internal fun requestToolFollowup(
        model: JSONObject,
        messages: JSONArray,
        root: File? = null,
        routeId: String = Route.ID,
        task: Task? = null
    ): String? {
        val apiKey = model.optString("api_key").ifBlank { root?.let { Route.apiKey(it) }.orEmpty() }
        val safeMessages = providerMessagesWithFullMemory(leanFollowup(messages))
            ?: providerMessagesWithFullMemory(messages)
            ?: throw ModelRequestFailure(
                "Не удалось собрать полный контекст памяти для продолжения ответа", 0L, "config"
            )
        return when (val result = Transport.chat(safeMessages, apiKey, 400)) {
            is Transport.Result.Success -> {
                if (root != null) runCatching { RouteMeta.recordSuccess(root, routeId, task) }
                result.reply
            }
            is Transport.Result.Failure -> {
                if (root != null) runCatching { RouteMeta.recordFailure(root, routeId, result.kind, task) }
                throw ModelRequestFailure(
                    RetryPolicy.explain(result.kind, result.httpStatus, result.detail),
                    if (RetryPolicy.isRetryable(result.kind)) RetryPolicy.AUTO_RETRY_DELAY_MS else 0L,
                    result.kind
                )
            }
        }
    }

    internal fun leanFollowup(messages: JSONArray): JSONArray {
        val out = JSONArray()
        if (messages.length() > 0) out.put(messages.optJSONObject(0))
        var lastCommander: JSONObject? = null
        val toolResults = mutableListOf<JSONObject>()
        for (i in 1 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role"); val content = m.optString("content")
            if (role == "user" && !content.startsWith("[ИНСТРУМЕНТ")) lastCommander = m
            if (role == "user" && content.startsWith("[ИНСТРУМЕНТ")) toolResults.add(m)
        }
        if (lastCommander != null) out.put(lastCommander)
        toolResults.takeLast(6).forEach { out.put(it) }
        return out
    }

    /** Search sources were removed with the search feature; kept for callers that pass it around. */
    internal fun sourceUrlsSnapshot(): JSONArray = JSONArray()

    internal fun appendFinalReply(
        root: File,
        reply: String,
        emotion: JSONObject? = null,
        meta: JSONObject? = null,
        routeName: String = Route.DISPLAY_NAME,
        commanderMessageId: String? = null
    ): String = appendFinalReplyInternal(root, reply, routeName, emotion, commanderMessageId)

    internal fun appendFinalReply(
        root: File,
        reply: String,
        routeName: String,
        sourceUrls: JSONArray = JSONArray(),
        emotion: JSONObject? = null,
        commanderMessageId: String? = null
    ): String = appendFinalReplyInternal(root, reply, routeName, emotion, commanderMessageId)

    private fun appendFinalReplyInternal(
        root: File,
        reply: String,
        routeName: String,
        emotion: JSONObject?,
        commanderMessageId: String?
    ): String {
        val id = java.util.UUID.randomUUID().toString()
        val event = JSONObject().put("id", id).put("type", "message")
            .put("source", "agent").put("who", "ruri").put("text", reply).put("route", routeName)
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

    private fun rememberFact(root: File, rawText: String): String {
        val text = SecretRedactor.redactText(rawText.trim()).take(300)
        if (text.length < 5 || text.contains(SecretRedactor.PLACEHOLDER)) {
            return "Факт не сохранён: текст содержит секрет или слишком короткий."
        }
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

    private fun readJson(root: File, path: String): JSONObject =
        try { JSONObject(FileManager.read(root, path)) } catch (_: Exception) { JSONObject() }
}
