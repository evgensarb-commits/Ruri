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

    /** Bound on tool round-trips in one turn: a confused model must not loop forever. */
    private const val MAX_TOOL_HOPS = 8

    /**
     * Tool output injected back into the context, in characters.
     *
     * Must stay above DocReader.PAGE_CHARS. It used to be 4000 against a 6000-character page, so
     * every document page was cut and its trailing "[ПРОДОЛЖЕНИЕ: page=N]" marker was cut with
     * it. She could not see that a next page existed, so she never asked for one.
     */
    private const val TOOL_RESULT_LIMIT = 9000

    /**
     * A long document is walked in one go, up to this many pages.
     *
     * Paired with the character budget below: 25 pages × 6000 chars would be 150k, so the
     * character cap is what actually stops a runaway file. Keeping these two consistent matters
     * — an earlier pair (40 pages against 24k chars) silently cut documents at four pages while
     * claiming a much larger limit.
     */
    private const val MAX_AUTO_PAGES = 25

    /** Total characters of document text handed over in one turn (~30 pages of prose). */
    private const val MAX_AUTO_CHARS = 120_000

    @Volatile private var lastFinalMessageId: String? = null
    private val responseLock = Any()

    internal class ModelRequestFailure(
        message: String,
        val retryAfterMs: Long = 0L,
        val failureKind: String = "provider"
    ) : IllegalStateException(message)

    fun respond(root: File, commanderMessageId: String? = null, batteryPercent: Int = 50): String = synchronized(responseLock) {
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
            return@synchronized respondInternal(root, targetId, batteryPercent)
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

    private fun respondInternal(root: File, commanderMessageId: String?, batteryPercent: Int): String {
        val apiKey = Route.apiKey(root)
        if (apiKey.isBlank()) {
            val detail = "ключ Routeway не задан"
            recordDiagnostic(root, "no_key", null, detail, JSONArray())
            OfflineQueue.markPending(root)
            throw ModelRequestFailure(RetryPolicy.explain("no_key"), 0L, "no_key")
        }
        if (Route.routerAiUnconfigured(root)) {
            // Ascertainable problem, not a transient one: explain it instead of sending a
            // request the provider would reject with an unclear 404.
            val detail = "routerai выбран, но модель не задана"
            recordDiagnostic(root, "config", null, detail, JSONArray())
            throw ModelRequestFailure(
                "Выбран routerai.ru, но идентификатор модели не задан. " +
                    "Открой «Настройки» → «Модель» и вставь точный id из каталога routerai.ru.",
                0L,
                "config"
            )
        }
        if (!NetState.online) {
            val detail = "Android сообщает об отсутствии сети"
            recordDiagnostic(root, "offline", null, detail, JSONArray())
            OfflineQueue.markPending(root)
            throw ModelRequestFailure(RetryPolicy.explain("offline"), 0L, "offline")
        }

        val messages = buildMessages(root, commanderMessageId, batteryPercent)
        if (!hasFullMemoryContext(messages)) {
            OfflineQueue.markPending(root)
            throw ModelRequestFailure(
                "Полная память не собрана: в контексте нет обязательных слоёв L0–L3. Запрос к модели не отправлен.",
                0L,
                "config"
            )
        }
        val safeMessages = providerMessagesWithFullMemory(messages) ?: messages

        // The newest Commander message decides the answer length. Read it here, in the
        // request path, so the budget is available before the call.
        val commanderText = FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 1000)
            .asReversed().asSequence().mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
            .firstOrNull { it.optString("type") == "message" && it.optString("who") == "commander" }
            ?.optString("text").orEmpty()

        val result = Transport.chat(
            safeMessages, apiKey, ChatStyle.replyBudget(root, commanderText),
            Route.model(root), Route.baseUrl(root)
        )
        when (result) {
            is Transport.Result.Success -> {
                recordDiagnostic(root, "ok", result.httpStatus, "", result.stages)
                runCatching { RouteMeta.recordAttempt(root, Route.providerId(root)) }
                runCatching { RouteMeta.recordHttpStatus(root, Route.providerId(root), result.httpStatus) }
                return finishTurn(root, result.reply, commanderMessageId, batteryPercent)
            }

            is Transport.Result.Failure -> {
                var kind = result.kind
                var detail = result.detail
                // MeroMero is only reachable through a VPN. A network-level failure while the
                // tunnel is down is really "no VPN", which is terminal and actionable.
                if (Route.requiresVpn(root) && !NetState.vpn &&
                    (kind == "network" || kind == "timeout" || kind == "server" || kind == "bad_response")
                ) {
                    kind = "no_vpn"
                    detail = detail.ifBlank { "VPN-туннель не обнаружен" }
                }
                recordDiagnostic(root, kind, result.httpStatus, detail, result.stages)
                runCatching { RouteMeta.recordAttempt(root, Route.providerId(root)) }
                if (result.httpStatus != null) {
                    runCatching { RouteMeta.recordHttpStatus(root, Route.providerId(root), result.httpStatus!!) }
                }
                runCatching { RouteMeta.recordFailure(root, Route.providerId(root), kind, Task.CHAT) }
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
    private fun finishTurn(
        root: File,
        rawReply: String,
        commanderMessageId: String?,
        batteryPercent: Int = 50
    ): String {
        val (replyWithoutDecision, _) = EmotionEngine.parseInitiativeDecision(rawReply)
        val (withoutEmotion, emotionDelta) = EmotionEngine.parseAndStrip(replyWithoutDecision)
        val emotion = if (emotionDelta != null) EmotionEngine.applyDelta(root, emotionDelta, "chat") else null

        // A tool request must be executed, not published. Previously the chat path only stripped
        // the JSON, so the tool call vanished and the answer became "(не смогла сформулировать
        // ответ)" — she silently lost the ability to read documents, pages and images.
        val (cleanReply, diaryTexts, toolRequests) = ToolLoop.stripActions(withoutEmotion)
        diaryTexts.forEach { routeChatDiaryAction(root, it, emotion) }

        val resolved = if (toolRequests.isNotEmpty()) {
            runToolRequests(root, withoutEmotion, toolRequests, emotion, commanderMessageId, batteryPercent)
        } else {
            resolveChatReply(cleanReply, diaryTexts.firstOrNull().orEmpty())
        }

        val reply = resolved
        if (recordAdultRefusal(
                root = root,
                routeId = Route.providerId(root),
                task = Task.CHAT,
                commanderText = currentCommanderText(root),
                reply = reply,
                diagnostic = JSONObject()
            )
        ) {
            throw ModelRequestFailure(RetryPolicy.explain("refusal"), 0L, "refusal")
        }

        runCatching { RouteMeta.recordSuccess(root, Route.providerId(root), Task.CHAT) }
        runCatching { OutcomeDigest.verifyClaims(root, reply) }
        val savedId = appendFinalReply(root, reply, emotion, null, Route.modelLabel(root), commanderMessageId)
        runCatching { BackupPolicy.checkpointState(root) }
        runCatching { OfflineQueue.clear(root) }
        lastFinalMessageId = savedId
        return reply
    }

    /**
     * Executes the tools she asked for, feeds the results back, and returns the final prose.
     *
     * Each round trip is bounded: a broken model could otherwise ask for tool after tool and
     * never answer. If the follow-up produces another tool request the loop continues, but the
     * hop count keeps it finite.
     */
    /** Test-only seam: exercises the remember-fact rules without a provider call. */
    internal fun rememberFactForTest(root: File, text: String): String = rememberFact(root, text)

    /**
     * Reads the rest of a document automatically.
     *
     * Paging existed, but only as an instruction: she had to notice the marker and ask for the
     * next page herself, and in practice she never did. Waiting for a model to remember a
     * protocol is a fragile design when the app already knows what page comes next. This walks
     * the document to the end, within one budget, and hands her the complete text once.
     *
     * Returns the joined text plus how many pages were read.
     */
    internal fun readWholeDocument(
        root: File,
        name: String,
        startPage: Int = 0,
        maxPages: Int = MAX_AUTO_PAGES,
        maxChars: Int = MAX_AUTO_CHARS
    ): Pair<String, Int> {
        val builder = StringBuilder()
        var page = startPage.coerceAtLeast(0)
        var read = 0
        var lastNote = ""
        // Distinguish "ran out of document" from "ran out of budget": only the second may be
        // reported as incomplete, otherwise a fully read file looks truncated.
        var reachedEnd = false
        var hitBudget = false
        while (read < maxPages) {
            if (builder.length >= maxChars) { hitBudget = true; break }
            val (ok, text) = DocReader.read(root, name, page)
            if (!ok) {
                if (read == 0) return text to 0
                lastNote = "\n[ЧТЕНИЕ ОСТАНОВЛЕНО: $text]"
                break
            }
            read++
            // Drop the per-page header and the trailing marker; the caller reports progress.
            val withoutHeader = if (text.contains('\n')) text.substringAfter('\n') else text
            val body = withoutHeader
                .substringBefore("\n[ПРОДОЛЖЕНИЕ")
                .substringBefore("\n[КОНЕЦ ДОКУМЕНТА]")
            builder.append(body.trim()).append("\n\n")
            val next = Regex("\\[ПРОДОЛЖЕНИЕ:.*?page=(\\d+)\\]").find(text)
                ?.groupValues?.get(1)?.toIntOrNull()
            if (next == null || next <= page) { reachedEnd = true; break }
            page = next
        }
        if (!reachedEnd && !hitBudget) hitBudget = read >= maxPages
        val more = when {
            lastNote.isNotBlank() -> lastNote
            hitBudget && !reachedEnd ->
                "\n[ПРОЧИТАНО $read страниц, дальше текст слишком велик для одного ответа. " +
                    "Спроси конкретное место или открой следующую страницу через read_document page=$page.]"
            else -> "\n[ДОКУМЕНТ ПРОЧИТАН ЦЕЛИКОМ: $read стр.]"
        }
        return (builder.toString().trimEnd() + more) to read
    }

    /**
     * Truncates a tool result without losing what tells her there is more.
     *
     * Two things must survive any cut: the paging marker (`[ПРОДОЛЖЕНИЕ: ... page=N]`,
     * `[КОНЕЦ ДОКУМЕНТА]`, the raw-journal cursor) and an honest note that the middle was
     * dropped. Silently cutting text made her believe a partial page was the whole document.
     */
    internal fun trimKeepingMarker(text: String, limit: Int = TOOL_RESULT_LIMIT): String {
        if (text.length <= limit) return text
        // Preserve every trailing bracket line: that is where continuation lives.
        val tailLines = ArrayList<String>()
        val lines = text.lines()
        for (line in lines.asReversed()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) break
            tailLines.add(0, trimmed)
        }
        // Also keep the "next offset" sentence the raw journal emits on its first line.
        val headNote = lines.firstOrNull { it.contains("после_смещения") || it.contains("Следующий") }
            ?.take(200)
        val tail = if (tailLines.isEmpty()) "" else "\n" + tailLines.joinToString("\n")
        val head = if (headNote.isNullOrBlank()) "" else headNote + "\n"
        val reserved = head.length + tail.length + 80
        val room = (limit - reserved).coerceAtLeast(200)
        val body = text.take(room)
        val dropped = text.length - body.length
        return head + body +
            "\n[ТЕКСТ ОБРЕЗАН: пропущено $dropped символов из ${text.length}. " +
            "Это не весь ответ инструмента; если нужен остаток — запроси следующую страницу.]" +
            tail
    }

    private fun runToolRequests(
        root: File,
        initialReply: String,
        requests: List<JSONObject>,
        emotion: JSONObject?,
        commanderMessageId: String?,
        batteryPercent: Int = 50
    ): String {
        val messages = buildMessages(root, commanderMessageId, batteryPercent)
        var current = initialReply
        var hops = 0
        // The model often answers with prose plus a JSON block. parseAction() only accepts a
        // bare object, so relying on it alone would drop the request it already made. The queue
        // from stripActions() is the source of truth for the first hop.
        var queued: List<JSONObject> = requests
        while (hops < MAX_TOOL_HOPS) {
            val action = queued.firstOrNull { ChatEngine.parseAction(it.toString()) != null }
                ?: parseAction(current)
                ?: break
            val name = action.optString("action")
            val params = JSONObject(action.toString())
            params.remove("action")

            FileManager.appendEvent(root, SecretRedactor.redactEvent(
                JSONObject().put("type", "tool_call").put("action", name).put("params", params)
            ))
            val result = executeTool(root, name, params)
            val safeText = trimKeepingMarker(SecretRedactor.redactText(result.second))
            FileManager.appendEvent(root, SecretRedactor.redactEvent(
                JSONObject().put("type", "tool_result").put("action", name)
                    .put("ok", result.first).put("text", safeText)
            ))

            // Saying a failure plainly matters: an empty result read as success makes her
            // invent content that the tool never returned.
            val status = if (result.first) "РЕЗУЛЬТАТ ИНСТРУМЕНТА" else "ИНСТРУМЕНТ НЕ СРАБОТАЛ"
            messages.put(JSONObject().put("role", "user")
                .put("content", "$status $name:\n$safeText\n\n" +
                    "Ответь обычной прозой по существу. Если инструмент не сработал — скажи " +
                    "об этом честно и не выдумывай содержимое. Не возвращай JSON, если не нужен " +
                    "ещё один инструмент."))
            queued = emptyList()
            current = requestToolFollowup(
                model = JSONObject().put("api_key", Route.apiKey(root)),
                messages = messages,
                root = root,
                routeId = Route.providerId(root),
                task = Task.CHAT,
                maxTokens = ChatStyle.replyBudget(root, currentCommanderText(root))
            ) ?: break
            hops++
        }
        // A hop limit reached on a usable answer is not a failure: she may have answered on the
        // last round trip. Returning the limit error here would throw away a good reply.
        val (cleaned, diaryAgain, leftover) = ToolLoop.stripActions(current)
        diaryAgain.forEach { routeChatDiaryAction(root, it, emotion) }
        if (leftover.isNotEmpty() && hops >= MAX_TOOL_HOPS) {
            return "Не удалось завершить цепочку действий: достигнут лимит $MAX_TOOL_HOPS вызовов."
        }
        return resolveChatReply(cleaned, diaryAgain.firstOrNull().orEmpty())
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
    internal fun buildMessages(root: File, commanderMessageId: String?, batteryPercent: Int = 50): JSONArray {
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
        // The newest Commander message decides the answer mode: short by default, detailed
        // when explicitly requested, role-play when the switch is on.
        val commanderText = FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 1000)
            .asReversed().asSequence().mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
            .firstOrNull { it.optString("type") == "message" && it.optString("who") == "commander" }
            ?.optString("text").orEmpty()

        val system = "Имя: ${character.optString("name")}; обозначение: ${character.optString("designation")}. " +
            "Личность: ${character.optString("persona")}. " +
            "Непрерывность: ${character.optString("continuity_rules")}. " +
            "Правила речи: ${character.optString("speech_rules")}. " +
            "Длина ответа: ${character.optString("length_policy")}. " +
            "Эмоции: ${character.optString("emotions")}. " +
            "Регуляторы характера: ${character.optJSONArray("regulators") ?: JSONArray()}. " +
            "Вход в сообщение придумывай сама, без шаблонов и заготовок. " +
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
            "Заряд батареи телефона прямо сейчас: $batteryPercent%. " +
            DeviceSensors.timeContext() + " " +
            "Активные цели: ${goals.optJSONArray("active") ?: JSONArray()}. " +
            "Незавершённые задачи: ${state.optJSONArray("active_tasks") ?: JSONArray()}. " +
            ChatStyle.architectureSection() +
            ChatStyle.memorySelfServiceSection() +
            ChatStyle.pendingInsightsSection(root) +
            ChatStyle.chatDirective(root, commanderText) +
            ChatStyle.webReadHint(commanderText) +
            // Only documents here: images are advertised separately, and mixing them meant a
            // markdown file could be listed as a document while an image was not.
            ChatStyle.documentHint(commanderText, DocReader.listDocumentNames(root)) +
            ChatStyle.imageHint(
                commanderText,
                DocReader.listImageNames(root),
                Vision.supportState(root) != false
            ) +
            // "без JSON" used to be absolute, which forbade the EMOTION_DELTA line the emotion
            // section above explicitly allows. The model obeyed the newer, stronger rule and
            // stopped reporting feelings — so the diary of emotions never moved. The exception
            // is now stated instead of left to be guessed.
            "Ты общаешься с Командиром в чате приложения. Отвечай обычной прозой, без " +
            "служебных блоков и без JSON-объектов инструментов. " +
            "Единственное исключение — служебная строка EMOTION_DELTA в конце, если она нужна: " +
            "она описана выше, в разделе про эмоции, и Командиру не показывается. " +
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
                .put("provider", Route.modelLabel(root))
                .put("model", Route.model(root))
                .put("base_url", Route.baseUrl(root))
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
            "recall" -> setOf("action", "query", "scope", "limit")
            "remember_fact" -> setOf("action", "text", "source_ids", "confidence")
            "self_check" -> setOf("action")
            "read_raw" -> setOf("action", "after_offset", "limit", "types", "from_ts", "to_ts")
            "challenge_claim" -> setOf("action", "store", "claim_id", "reason")
            "read_url" -> setOf("action", "url")
            "read_document" -> setOf("action", "name", "page")
            "look_at_image" -> setOf("action", "name")
            else -> return null
        }
        val keys = action.keys()
        while (keys.hasNext()) if (keys.next() !in allowed) return null
        when (name) {
            "recall" -> {
                if (action.optString("query").isBlank() || action.optString("query").length > 300) return null
                if (action.has("limit") && action.optInt("limit", 0) !in 1..MemoryRetriever.SCOPE_MAX_RESULTS) return null
                val scope = action.optString("scope")
                if (scope.isNotBlank() && scope.lowercase() !in setOf(
                        MemoryRetriever.SCOPE_ALL, MemoryRetriever.SCOPE_MESSAGES,
                        MemoryRetriever.SCOPE_FACTS, MemoryRetriever.SCOPE_DIARY,
                        MemoryRetriever.SCOPE_NOTES
                    )) return null
            }
            "remember_fact" -> if (action.optString("text").trim().length !in 5..300) return null
            "read_raw" -> {
                // Bounded by construction: the tool cannot ask for the whole journal.
                if (action.has("after_offset") && action.optLong("after_offset", -1L) < 0L) return null
                if (action.has("limit") && action.optInt("limit", 0) !in 1..200) return null
            }
            "challenge_claim" -> {
                if (action.optString("store").uppercase() !in setOf("L1", "L2")) return null
                if (action.optString("claim_id").isBlank()) return null
            }
            "read_url" -> {
                val url = action.optString("url").trim()
                if (url.length > 2048) return null
                if (!Regex("(?i)^https?://").containsMatchIn(url)) return null
                if (UrlReader.isDeniedUrl(url)) return null
            }
            "read_document" -> {
                if (action.optString("name").isBlank() || action.optString("name").length > 160) return null
                if (action.has("page") && action.optInt("page", -1) < 0) return null
            }
            "look_at_image" -> {
                if (action.optString("name").isBlank() || action.optString("name").length > 160) return null
            }
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
                    MemoryRetriever.search(
                        root = root,
                        query = params.optString("query"),
                        scope = params.optString("scope", MemoryRetriever.SCOPE_ALL),
                        limit = params.optInt("limit", 12)
                    )
                )
                "remember_fact" -> true to SecretRedactor.redactText(
                    rememberFact(
                        root,
                        params.optString("text"),
                        params.opt("source_ids"),
                        params.optDouble("confidence", -1.0)
                    )
                )
                "self_check" -> true to OutcomeDigest.build(root)
                "read_raw" -> {
                    // Read-only by design: the journal is never edited from here.
                    val types = HashSet<String>()
                    when (val raw = params.opt("types")) {
                        is JSONArray -> for (i in 0 until raw.length()) types.add(raw.optString(i))
                        is String -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { types.add(it) }
                    }
                    val (entries, next) = RawLog.read(
                        root = root,
                        afterOffset = params.optLong("after_offset", 0L),
                        limit = params.optInt("limit", 30),
                        types = types,
                        fromTs = params.optLong("from_ts", 0L),
                        toTs = params.optLong("to_ts", 0L)
                    )
                    val body = StringBuilder()
                    body.append("ЖУРНАЛ, ${entries.size} записей. Следующий после_смещения: $next ")
                    body.append("(всего ${RawLog.sizeBytes(root)} байт)\n")
                    for (entry in entries) body.append(RawLog.describe(entry)).append('\n')
                    if (entries.isEmpty()) body.append("В этом окне подходящих записей нет.\n")
                     true to body.toString()
                }
                "challenge_claim" -> {
                    val store = params.optString("store").uppercase()
                    val claimId = params.optString("claim_id")
                    val ok = Provenance.challenge(root, store, claimId, params.optString("reason"))
                    ok to if (ok) {
                        "Утверждение $claimId помечено как оспоренное. Ничего не удалено."
                    } else {
                        "Утверждение $claimId не найдено в $store."
                    }
                }
                "read_url" -> {
                    // The Commander supplied this address; UrlReader refuses local and
                    // service addresses, so the model cannot probe the phone's network.
                    val (ok, text) = UrlReader.read(root, params.optString("url"))
                    ok to text
                }
                "read_document" -> {
                    // Read the whole document unless a specific page was requested. Asking her to
                    // page manually meant long files were never actually read.
                    val requestedPage = params.optInt("page", -1)
                    if (requestedPage >= 0) {
                        val (ok, text) = DocReader.read(
                            root, params.optString("name"), requestedPage
                        )
                        ok to text
                    } else {
                        val (text, pages) = readWholeDocument(root, params.optString("name"))
                        (pages > 0) to text
                    }
                }
                "look_at_image" -> {
                    // Standalone image or one inside a document, described by the provider.
                    val name = DocReader.sanitizeName(params.optString("name"))
                    val file = File(File(root, DocReader.DIR), name)
                    if (!file.isFile) {
                        false to "Изображение «$name» не найдено"
                    } else {
                        val bytes = file.readBytes()
                        val mime = Vision.mimeOf(bytes)
                        if (mime == null) {
                            false to "Файл «$name» не является изображением"
                        } else {
                            val (text, reason) = Vision.describeDetailed(
                                root, bytes, mime,
                                "Опиши изображение подробно: что на нём, люди, текст, стиль. " +
                                    "Не выдумывай детали."
                            )
                            if (text == null) false to reason else true to text
                        }
                    }
                }
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
        routeId: String = Route.ID_ROUTEWAY,
        task: Task? = null,
        maxTokens: Int = 400
    ): String? {
        val apiKey = model.optString("api_key").ifBlank { root?.let { Route.apiKey(it) }.orEmpty() }
        // The provider id comes from the config when a root is available; otherwise fall back
        // to the id the caller already loaded into the model object.
        val modelId = root?.let { Route.model(it) }
            ?: model.optString("model").ifBlank { Route.MODEL_FREE }
        val safeMessages = providerMessagesWithFullMemory(leanFollowup(messages))
            ?: providerMessagesWithFullMemory(messages)
            ?: throw ModelRequestFailure(
                "Не удалось собрать полный контекст памяти для продолжения ответа", 0L, "config"
            )
        return when (val result = Transport.chat(
            safeMessages, apiKey, maxTokens, modelId,
            root?.let { Route.baseUrl(it) } ?: Route.ROUTEWAY_BASE_URL
        )) {
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
        routeName: String = Route.ROUTEWAY_BASE_URL.let { "Routeway" },
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

    private fun rememberFact(
        root: File,
        rawText: String,
        rawSources: Any? = null,
        confidenceOverride: Double = -1.0
    ): String {
        val text = SecretRedactor.redactText(rawText.trim()).take(300)
        if (text.length < 5 || text.contains(SecretRedactor.PLACEHOLDER)) {
            return "Факт не сохранён: текст содержит секрет или слишком короткий."
        }

        // Sources are what separate a fact from a guess. They are verified against the journal,
        // so an invented id cannot make a claim look confirmed.
        val claimed = ArrayList<String>()
        when (rawSources) {
            is org.json.JSONArray -> for (i in 0 until rawSources.length()) claimed.add(rawSources.optString(i))
            is String -> rawSources.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { claimed.add(it) }
        }
        val verified = Provenance.verifiedSources(root, claimed)

        val file = runCatching { JSONObject(FileManager.read(root, "memory/L1_facts.json")) }
            .getOrElse { JSONObject().put("facts", JSONArray()) }
        val facts = file.optJSONArray("facts") ?: JSONArray().also { file.put("facts", it) }
        fun normalized(value: String) = value.lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
        val key = normalized(text)
        val now = System.currentTimeMillis()

        for (i in 0 until facts.length()) {
            val existing = facts.optJSONObject(i) ?: continue
            if (normalized(existing.optString("text")) != key) continue
            // A rejected claim stays rejected: repeating it does not silently revive it.
            if (existing.optString("status") == Provenance.STATUS_REJECTED) {
                return "Похожий факт уже был отвергнут Командиром; повторно не добавляю."
            }
            existing.put("confirmations", existing.optInt("confirmations", 1) + 1)
            existing.put("last_confirmed_at", now)
            val merged = LinkedHashSet<String>()
            existing.optJSONArray("source_event_ids")?.let { arr ->
                for (k in 0 until arr.length()) merged.add(arr.optString(k))
            }
            merged.addAll(verified)
            existing.put("source_event_ids", JSONArray(merged.filter { it.isNotBlank() }))
            if (existing.optString("status") == Provenance.STATUS_ASSUMPTION && verified.isNotEmpty()) {
                existing.put("status", Provenance.STATUS_ASSERTED)
            }
            FileManager.write(root, "memory/L1_facts.json", file.put("updated_at", now).toString(2))
            return "Похожий факт уже есть в L1; подтверждений теперь ${existing.optInt("confirmations")}."
        }

        // Full store: evict by weight instead of refusing the new fact. The old behaviour threw
        // the new one away and kept whatever was oldest, which is exactly backwards.
        val id = "self-" + key.hashCode().toUInt().toString(16)
        if (facts.length() >= ReflectionPass.FACT_CAP) {
            MemoryWeight.makeRoom(root, "L1", facts, ReflectionPass.FACT_CAP, id)
        }
        val confidence = if (confidenceOverride >= 0.0) confidenceOverride
        else if (verified.isEmpty()) 0.25 else 0.7
        facts.put(JSONObject()
            .put("id", id)
            .put("text", text)
            .put("category", "self_reported")
            .put("source_event_ids", JSONArray(verified))
            .put("origin", if (verified.isEmpty()) "assumption" else "self_reported")
            .put("confidence", confidence)
            .put("status", Provenance.statusFor(verified))
            .put("confirmations", if (verified.isEmpty()) 0 else 1)
            .put("created_at", now)
            .put("updated_at", now)
            .put("last_confirmed_at", now))
        FileManager.write(root, "memory/L1_facts.json", file.put("updated_at", now).toString(2))
        return if (verified.isEmpty()) {
            "Запомнила как предположение: не указаны события-основания."
        } else {
            "Запомнила как факт, оснований: ${verified.size}."
        }
    }

    private fun readJson(root: File, path: String): JSONObject =
        try { JSONObject(FileManager.read(root, path)) } catch (_: Exception) { JSONObject() }
}
