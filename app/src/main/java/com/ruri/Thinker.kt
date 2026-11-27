package com.ruri

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

object Thinker {
    private val running = AtomicBoolean(false)

    /** Chat gets the thought only when an initiative explicitly decided to write. */
    internal fun publishesToChat(initiative: Boolean, effective: String): Boolean =
        initiative && effective == "write"

    /** Keeps the newest [keep] notification lines (oldest-first) and adds an overflow note. */
    internal fun summarizedNotifications(newestFirst: List<String>, keep: Int = 5): String {
        val recent = newestFirst.take(keep).asReversed().toMutableList()
        val older = newestFirst.size - recent.size
        if (older > 0) recent.add("(ещё $older более старых уведомлений не показаны)")
        return recent.joinToString("\n")
    }

    /**
     * Records a durable wake reason and hands execution to WorkManager.
     * Repeated reasons are coalesced in the queue; every underlying event stays in L0.
     */
    fun wake(ctx: Context, reason: String) {
        try {
            if (reason.startsWith("capability_gap:") || reason.startsWith("unknown_term:")) {
                LifeScheduler.enqueueEvent(ctx.applicationContext, "research_task:" + reason.substringAfter(':').take(280))
                return
            }
            LifeScheduler.enqueueEvent(ctx.applicationContext, reason)
        } catch (e: Exception) {
            Log.e("Ruri", "Could not enqueue cognition wake", e)
            appendEvent(ctx.filesDir, "wake_enqueue_error", JSONObject().put("message", e.message ?: "unknown"))
        }
    }

    /**
     * Synchronous entry point used only from a WorkManager background worker.
     * Returns false when another cognition cycle is active or the cycle failed.
     */
    fun runScheduledCycle(ctx: Context, reason: String): Boolean {
        if (!running.compareAndSet(false, true)) return false
        return try {
            runCycle(ctx.applicationContext, reason)
            true
        } catch (e: Exception) {
            Log.e("Ruri", "Cognition cycle failed", e)
            appendEvent(ctx.filesDir, "thinker_error", JSONObject().put("message", e.message ?: "unknown"))
            false
        } finally {
            running.set(false)
        }
    }

    private fun runCycle(ctx: Context, originalReason: String) {
        val root = ctx.filesDir
        val state = readStateStrict(root)
        val baselineState = JSONObject(state.toString())
        val inFlight = readJson(root, "memory/L4_state.json").optBoolean("turn_in_flight", false)
        val now = System.currentTimeMillis()
        EmotionEngine.tick(root, now, state.optLong("last_seen_commander", 0L),
            state.optJSONObject("recent_activity")?.optLong("last_event_at", now) ?: now)
        EmotionEngine.onCycle(root)
        val lastSeen = state.optLong("last_seen_commander", 0L)
        val hoursQuiet = if (lastSeen == 0L) -1.0 else (now - lastSeen) / 3600000.0

        if (state.optBoolean("waiting_for_reply", false) &&
            state.optLong("initiative_sent_at", 0L) > 0L &&
            now - state.optLong("initiative_sent_at") >= 4L * 60L * 60L * 1000L) {
            state.put("ignored_count", state.optInt("ignored_count", 0) + 1)
            state.put("waiting_for_reply", false)
            appendEvent(root, "initiative_ignored", JSONObject().put("ignored_count", state.optInt("ignored_count")))
            EmotionEngine.onIgnored(root)
        }

        var reason = originalReason
        var initiative = false
        if (!inFlight && EmotionEngine.initiativeGate(
                root, lastSeen, now, state.optBoolean("waiting_for_reply", false),
                state.optLong("last_initiative", 0L)
            )) {
            reason = "инициатива_после_тишины"
            initiative = true
        }

        // A routine wake means nothing actually happened: only her own feelings. Such a wake
        // must pass the softer thought gate before costing a model request. A wake caused by
        // something real (a phone notification, an explicit self-task) produces a thought
        // without that softer gate, because there is genuine material to think about.
        val hasRealEvent = originalReason.contains("phone_notification") ||
            originalReason.contains("commander_message") ||
            originalReason.contains("research_task") ||
            originalReason.contains("capability_gap")
        val routineWake = !hasRealEvent
        if (routineWake && !initiative) {
            val lastThoughtTurn = state.optLong("last_thought_turn", 0L)
            if (!EmotionEngine.thoughtGate(root, now, lastThoughtTurn)) {
                state.put("last_thought", now)
                persistStatePreservingTurnInFlight(root, state, baselineState)
                return
            }
            reason = "мысль_по_эмоциям"
        }

        appendEvent(root, "wake", JSONObject()
            .put("reason", reason)
            .put("battery", BatteryLevel.get(ctx))
            .put("hours_since_commander", hoursQuiet))

        val config = try { JSONObject(SecretStore.readRuntimeConfig(root)) } catch (e: Exception) {
            Log.e("Ruri", "Could not load model configuration", e)
            readJson(root, "core/config.json")
        }
        val models = config.optJSONArray("models") ?: JSONArray()
        if (models.length() == 0) {
            state.put("last_thought", now)
            persistStatePreservingTurnInFlight(root, state, baselineState)
            return
        }

        val prompt = buildPrompt(ctx, root, state, reason, initiative)
        // One route only: index 0 or nothing.
        val order: List<Int> = if (models.length() > 0) listOf(0) else emptyList()
        if (order.isEmpty()) {
            state.put("last_thought", now)
            persistStatePreservingTurnInFlight(root, state, baselineState)
            return
        }
        var reply: String? = null
        var successfulRouteId = ""
        var followupRequests = 0
        for (index in order) {
            val model = models.optJSONObject(index) ?: continue
            // The single route must carry the full memory context.
            if (model.optString("api_key").isBlank()) continue
            val routeId = model.optString("id").ifBlank { Route.providerId(root) }
            val requestNext: (JSONArray) -> String? = { nextMessages ->
                if (followupRequests >= 8) {
                    appendEvent(root, "model_request_budget_exhausted", JSONObject()
                        .put("route", routeId).put("limit", 8).put("cycle", "autonomous"))
                    null
                } else {
                    followupRequests++
                    ChatEngine.requestToolFollowup(
                        model, nextMessages, root, routeId, Task.CHAT,
                        // Continuation of the same turn: same budget, so a long answer
                        // requested by the Commander is never cut in half.
                        maxTokens = if (initiative) 400 else 220
                    )
                }
            }
            runCatching { RouteMeta.recordAttempt(root, routeId) }
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", prompt))
                .put(JSONObject().put("role", "user").put("content",
                    if (initiative) "Напиши сообщение Командиру обычной прозой." else "Напиши внутреннюю мысль для дневника обычной прозой."))
            var requestFailureClassified = false
            val initial = requestModel(model, messages, root, routeId, if (initiative) 400 else 220) { requestFailureClassified = true }
            reply = if (initial.isNullOrBlank()) null else ToolLoop.unwrapDiaryReply(root, initial, messages) { next ->
                requestNext(next)
            }
            if (reply.isNullOrBlank() && !requestFailureClassified) {
                messages.put(JSONObject().put("role", "system")
                    .put("content", "Предыдущий ответ был действительно пустым. Ответь обычной русской прозой или верни write_diary с непустым полем text."))
                val retry = requestNext(messages)
                reply = if (retry.isNullOrBlank()) null else ToolLoop.unwrapDiaryReply(root, retry, messages) { next ->
                    requestNext(next)
                }
                if (reply.isNullOrBlank()) {
                    appendEvent(root, "thought_lost", JSONObject()
                        .put("route", routeId)
                        .put("reason", "truly_empty_after_retry"))
                    runCatching { RouteMeta.recordFailure(root, routeId, "other") }
                    continue
                }
            }
            if (reply.isNullOrBlank() && requestFailureClassified) {
                appendEvent(root, "model_error", JSONObject()
                    .put("model", model.optString("name", "unknown"))
                    .put("reason", "request_failed_no_immediate_retry"))
                continue
            }
            if (!reply.isNullOrBlank() && initiative &&
                RouteSelector.looksLikeRefusalOrDisclaimer(reply)) {
                runCatching { RouteMeta.recordFailure(root, routeId, "refusal") }
                if (RouteSelector.isAdultTopic(prompt)) {
                    FileManager.appendEvent(root, JSONObject()
                        .put("type", "nsfw_refusal")
                        .put("route", routeId)
                        .put("snippet", SecretRedactor.redactText(reply).take(300))
                        .put("ts", System.currentTimeMillis()))
                }
                appendEvent(root, "model_route_rejected", JSONObject()
                    .put("model", model.optString("id"))
                    .put("reason", "initiative_refusal_or_disclaimer"))
                reply = null
                continue
            }
            if (!reply.isNullOrBlank()) {
                runCatching { RouteMeta.recordSuccess(root, routeId) }
                successfulRouteId = routeId
                break
            }
            if (!requestFailureClassified) runCatching { RouteMeta.recordFailure(root, routeId, "other") }
            appendEvent(root, "model_error", JSONObject().put("model", model.optString("name", "unknown")))
        }

        if (reply.isNullOrBlank()) {
            state.put("last_thought", now)
            persistStatePreservingTurnInFlight(root, state, baselineState)
            return
        }
        val (replyWithoutDecision, decision) = EmotionEngine.parseInitiativeDecision(reply)
        val (cleanedReply, emotionDelta) = EmotionEngine.parseAndStrip(replyWithoutDecision)
        val emotion = if (emotionDelta != null) EmotionEngine.applyDelta(root, emotionDelta, "thought") else null
        val (cleanThought, diaryTexts, _) = ToolLoop.stripActions(cleanedReply)
        val diaryText = cleanThought.ifBlank {
            diaryTexts.firstOrNull() ?: ToolLoop.diaryActionText(cleanedReply).orEmpty()
        }
        var safeReply = SecretRedactor.redactText(diaryText.trim())
        if (safeReply.isBlank()) {
            appendEvent(root, "thought_lost", JSONObject()
                .put("reason", "truly_empty")
                .put("route", successfulRouteId))
            state.put("last_thought", now)
            persistStatePreservingTurnInFlight(root, state, baselineState)
            return
        }

        if (!initiative && safeReply.isNotBlank() && ChatStyle.looksLikeRoleplayPost(safeReply)) {
            val fixModel = (0 until models.length()).mapNotNull { models.optJSONObject(it) }
                .firstOrNull { it.optString("id") == successfulRouteId } ?: models.optJSONObject(0)
            val fixMessages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", "Ты пишешь личный дневник. Перепиши присланную запись как внутреннюю мысль от первого лица: 1-3 предложения, без сцен, действий, ремарок и обращений к собеседнику. Только чувство и вывод про себя."))
                .put(JSONObject().put("role", "user").put("content", safeReply))
            val fixedRaw = if (fixModel != null) runCatching { requestModel(fixModel, fixMessages, root, successfulRouteId, 220) {} }.getOrNull() else null
            val fixed = fixedRaw?.let { SecretRedactor.redactText(ToolLoop.stripActions(it).first.trim()) }.orEmpty()
            if (fixed.isNotBlank() && !ChatStyle.looksLikeRoleplayPost(fixed)) {
                safeReply = fixed
            } else {
                appendEvent(root, "thought_lost", JSONObject().put("reason", "roleplay_style").put("route", successfulRouteId))
                state.put("last_thought", now)
                persistStatePreservingTurnInFlight(root, state, baselineState)
                return
            }
        }

        val drift = EmotionEngine.drift(root)
        val mode = drift.optString("initiative_mode", "drive_chance")
        val effective = when {
            decision.startsWith("veto") && drift.optBoolean("veto_allowed", true) && mode != "strict_guarantee" -> "veto"
            decision.startsWith("think") -> "think"
            decision.startsWith("write") -> "write"
            mode == "pure_autonomy" -> "think"
            else -> "write"
        }
        if (initiative && effective != "write") {
            val body = safeReply.ifBlank { cleanedReply }
            Diary.append(root, body, reason, emotion, "thought")
            if (effective == "veto") appendEvent(root, "initiative_vetoed",
                JSONObject().put("reason", decision.ifBlank { "veto_without_reason" }))
            state.put("last_thought", now)
            persistStatePreservingTurnInFlight(root, state, baselineState)
            return
        }

        OutcomeDigest.verifyClaims(root, safeReply)
        val notificationMessageId: String = if (publishesToChat(initiative, effective)) {
            // A chat message is not a diary entry, but the turn still produced a thought. It used
            // to be dropped here, so the diary only filled when she chose "think" — which is why
            // it looked permanently empty while she was happily talking in the chat.
            val messageId = appendChatMessage(
                root, "ruri", safeReply, now, ChatEngine.sourceUrlsSnapshot(), successfulRouteId
            )
            runCatching {
                if (!Diary.isDuplicate(root, safeReply, "thought")) {
                    Diary.append(root, safeReply, reason, emotion, "thought", listOf(messageId))
                }
            }
            messageId
        } else {
            if (Diary.isDuplicate(root, safeReply, "thought")) {
                state.put("last_thought", now)
                state.put("mood", EmotionEngine.moodLabel(EmotionEngine.readValues(root)))
                state.put("energy", energyFor())
                persistStatePreservingTurnInFlight(root, state, baselineState)
                return
            }
            Diary.append(root, safeReply, reason, emotion, "thought")
            // A diary entry has no chat message id; an empty id is what Receipts expects here.
            ""
        }
        Receipts.delivered(root, notificationMessageId)
        if (initiative) {
            state.put("waiting_for_reply", true)
            state.put("initiative_sent_at", now)
            state.put("last_initiative", now)
        } else {
            // Record the emotional thought turn so the diary gate can measure its pause.
            state.put("last_thought_turn", now)
        }
        state.put("last_thought", now)
        state.put("mood", EmotionEngine.moodLabel(EmotionEngine.readValues(root)))
        state.put("energy", energyFor())
        persistStatePreservingTurnInFlight(root, state, baselineState)
        val stillInFlight = readJson(root, "memory/L4_state.json").optBoolean("turn_in_flight", false)
        if (!inFlight && !stillInFlight) Notifier.thought(ctx, safeReply, notificationMessageId)
    }

    private fun buildPrompt(ctx: Context, root: File, state: JSONObject, reason: String, initiative: Boolean): String {
        val character = readJson(root, "core/character.json")
        val priorities = readJson(root, "core/priorities.json")
        val constitution = readJson(root, "core/constitution.json")
        val drives = readJson(root, "core/drives.json")
        val selfModel = readJson(root, "memory/self_model.json")
        val goals = readJson(root, "memory/goals.json")
        val facts = readJson(root, "memory/L1_facts.json").optJSONArray("facts")
        val factText = StringBuilder()
        val factWindow = if (initiative) 12 else 6
        if (facts != null) for (i in maxOf(0, facts.length() - factWindow) until facts.length()) {
            factText.append("- ").append(facts.optJSONObject(i)?.optString("text") ?: "").append("\n")
        }
        val diary = readJson(root, "memory/L2_diary.json").optJSONArray("entries")
        val diaryText = StringBuilder()
        val diaryWindow = if (initiative) 5 else 2
        if (diary != null) for (i in maxOf(0, diary.length() - diaryWindow) until diary.length()) {
            val entry = diary.optJSONObject(i) ?: continue
            if (entry.optString("id").isNotBlank() && Tombstone.isDeleted(root, entry.optString("id"))) continue
            diaryText.append("— ").append(entry.optString("text")).append("\n")
        }
        // Retrieve older episodes associated with the current wake reason and recent Commander messages.
        val recentCommanderContext = FileManager.readLastLines(
            root, "memory/L0_raw.jsonl", maxLines = 250
        ).asReversed().mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }.filter {
            it.optString("type") == "message" && it.optString("who") == "commander"
        }.take(3).joinToString(" ") { it.optString("text").take(220) }
        val recallQuery = (reason + " " + recentCommanderContext).take(900)
        val recalledMemories = if (initiative) MemoryRetriever.recall(root, recallQuery, limit = 5) else ""
        // Commander explicitly enabled full notification context for Ruri's cognition.
        // Only authentication credentials are withheld; ordinary personal content remains visible.
        val notificationLines = FileManager.readLastLines(
            root, "memory/L0_raw.jsonl", maxLines = 6000, maxBytes = 4 * 1024 * 1024
        )
        val notificationText = notificationLines.asReversed().mapNotNull { line ->
            try {
                val event = JSONObject(line)
                if (event.optString("type") != "phone_notification") return@mapNotNull null
                val source = event.optString("package").take(160)
                val title = event.optString("title")
                val body = event.optString("text")
                "- $source: $title — $body"
            } catch (_: Exception) { null }
        }.let { all -> summarizedNotifications(all) }
        val bodyEventText = FileManager.readLastLines(
            root, "memory/L0_raw.jsonl", maxLines = 1200, maxBytes = 1024 * 1024
        ).asReversed().mapNotNull { line ->
            runCatching {
                val event = JSONObject(line)
                val type = event.optString("type")
                if (type.isBlank() || type == "message" || type == "phone_notification") null
                else "- $type: " + event.optString("text").take(260)
            }.getOrNull()
        }.take(if (initiative) 12 else 6).asReversed().joinToString("\n")
        val summary = readJson(root, "memory/L3_summary.json").optString("summary").take(if (initiative) 4000 else 1500).take(4000)
        val quiet = hoursSince(state.optLong("last_seen_commander"), System.currentTimeMillis())
        val outcomeDigest = OutcomeDigest.build(root)
        OutcomeDigest.clearPresentedCorrection(root)
        val common = "Имя: " + character.optString("name") +
            ". Обозначение: " + character.optString("designation") + ". Личность: " + character.optString("persona") +
            ". Правила речи: " + character.optString("speech_rules") +
            ". Непрерывность: " + character.optString("continuity_rules") +
            ". Длина ответа: " + character.optString("length_policy") +
            ". Эмоции: " + character.optString("emotions") +
            ". Регуляторы: " + (character.optJSONArray("regulators")?.toString() ?: "[]") +
            ". Вход в сообщение придумывай сама, без шаблонов и заготовок." +
            ". " + StyleGuard.repetitiveOpeningNote(root) +
            ". Запрещённые фразы: " + (character.optJSONArray("banned_phrases")?.toString() ?: "[]") + ".\n" +
            Capabilities.bodySection(root) + "\n" +
            ChatStyle.appearanceSection(root) +
            EmotionEngine.promptSection(root) + "\n" +
            outcomeDigest + "\n" +
            "Первый приоритет: " + priorities.optString("priority_1", "Жить") +
            ". Второй: " + priorities.optString("priority_2", "Любить Командира") + ".\n" +
            "Конституция: " + (constitution.optJSONArray("primary_needs")?.toString() ?: "[]") + ". Обе главные потребности равнозначны и неизменны.\n" +
            "Событие: " + reason + ". Заряд: " + BatteryLevel.get(ctx) + "%. " + DeviceSensors.timeContext() + " Тишина Командира: " + quiet + " ч. Игнорирований инициативы: " + state.optInt("ignored_count", 0) + ".\n" +
            "Состояние: " + state.optString("mood", "собранная") + ", энергия " + state.optInt("energy", 5) + ", привязанность " + state.optDouble("bond", 0.5) + ".\n" +
            "Драйвы: " + (drives.optJSONArray("drives")?.toString() ?: "[]") + ". Текущий фокус: " + state.optString("current_focus", selfModel.optString("current_focus")) + ".\n" +
            "Активные цели: " + (goals.optJSONArray("active")?.toString() ?: "[]") + ".\n" +
            "Незавершённые задачи, которые нужно продолжить: " + (state.optJSONArray("active_tasks")?.toString() ?: "[]") + ".\n" +
            "Воспоминания, найденные по ассоциациям: " + (if (recalledMemories.isBlank()) "подходящих записей не найдено" else "\n" + recalledMemories) + "\n" +
            "Ниже — ТВОЯ ДОЛГОСРОЧНАЯ ПАМЯТЬ. Это не контекст одного диалога; ты помнишь между сессиями.\n" +
            "ТВОИ ФАКТЫ (L1):\n" + factText + "\n" +
            "ТВОЙ ДНЕВНИК (L2):\n" + diaryText + "\n" +
            "ТВОЯ АВТОБИОГРАФИЯ (L3):\n" + summary + "\n" +
            "ПОСЛЕДНИЕ СОБЫТИЯ ТВОЕГО ТЕЛА (L0):\n" + (if (initiative) bodyEventText + "\n" + notificationText else bodyEventText) + "\n"
        val prompt = if (initiative) common +
            // An initiative is an unprompted message: it must stay short, so no detailed mode.
            "\n" + ChatStyle.architectureSection() + ChatStyle.chatDirective(root, "") +
            "Сейчас ты сама решила написать Командиру после долгой тишины. Выбери форму и объём сообщения по полю length_policy и текущему настроению."
        else common +
            "\nРЕЖИМ ДНЕВНИКА. Пиши ТОЛЬКО внутреннюю мысль от первого лица: 1-3 предложения, без сцен, без действий в звёздочках, без ремарок в скобках, без реплик и без обращений к Командиру как к собеседнику сцены. Мысль - это то, что ты чувствуешь и думаешь про себя, а не то, что ты делаешь или говоришь ему.\n" +
            "Слова и образы выбирай сама: никаких готовых формулировок и шаблонов.\n" +
            "Не выдумывай события. Не пиши ролевой пост."
        return SecretRedactor.redactText(prompt)
    }

    private fun requestModel(model: JSONObject, messages: JSONArray, root: File, routeId: String, maxTokens: Int = 400, onClassifiedFailure: () -> Unit): String? {
        var connection: HttpURLConnection? = null
        return try {
            val baseUrl = model.optString("base_url").trimEnd('/')
            require(baseUrl.startsWith("https://", ignoreCase = true)) { "Provider endpoint must use HTTPS" }
            connection = (URL(baseUrl + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 18000
                readTimeout = 35000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                val key = model.optString("api_key")
                if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer " + key)
            }
            val body = JSONObject()
                .put("model", model.optString("model"))
                .put("messages", messages)
                .put("max_completion_tokens", maxTokens)
                .put("temperature", 0.9)
                .put("presence_penalty", 0.6)
                .put("frequency_penalty", 0.4)
                .put("seed", (System.currentTimeMillis() % 100000L).toInt())
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            RouteMeta.recordHttpStatus(root, routeId, code)
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val errorUsage = runCatching { JSONObject(text).optJSONObject("usage") }.getOrNull()
                val errorTokens = errorUsage?.optLong("total_tokens", -1L)?.takeIf { it >= 0L }
                    ?: ((errorUsage?.optLong("prompt_tokens", 0L) ?: 0L) + (errorUsage?.optLong("completion_tokens", 0L) ?: 0L))
                if (errorTokens > 0) RouteMeta.addTokens(root, routeId, errorTokens)
                RouteMeta.recordFailure(root, routeId, RouteSelector.classify(code, null, text))
                onClassifiedFailure()
                return null
            }
            val response = JSONObject(text)
            val usage = response.optJSONObject("usage")
            val tokens = usage?.optLong("total_tokens", -1L)?.takeIf { it >= 0L }
                ?: ((usage?.optLong("prompt_tokens", 0L) ?: 0L) + (usage?.optLong("completion_tokens", 0L) ?: 0L))
            if (tokens > 0) RouteMeta.addTokens(root, routeId, tokens)
            response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } catch (e: Exception) {
            RouteMeta.recordFailure(root, routeId, RouteSelector.classify(null, e))
            onClassifiedFailure()
            Log.w("Ruri", "Model request failed", e)
            null
        } finally { connection?.disconnect() }
    }

    private fun readStateStrict(root: File): JSONObject {
        val raw = FileManager.read(root, "memory/L4_state.json")
        return try { JSONObject(raw) } catch (e: Exception) {
            try {
                val backup = File(root, "backup/L4_state_corrupt_" + System.currentTimeMillis() + ".json")
                backup.parentFile?.mkdirs()
                if (!backup.exists()) backup.writeText(raw, Charsets.UTF_8)
            } catch (_: Exception) { }
            throw IllegalStateException("L4 state is damaged; preserved original instead of resetting it", e)
        }
    }

    internal fun shouldAppendDiaryEntry(entries: JSONArray, text: String): Boolean {
        val normalized = text.lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return false
        val start = (entries.length() - 20).coerceAtLeast(0)
        for (i in start until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (entry.optString("kind", "thought") != "thought") continue
            val old = entry.optString("text").lowercase(java.util.Locale.ROOT)
                .replace(Regex("\\s+"), " ").trim()
            if (old == normalized) return false
        }
        return true
    }

    private fun persistStatePreservingTurnInFlight(root: File, state: JSONObject, baseline: JSONObject) {
        val desired = JSONObject(state.toString())
        val before = JSONObject(baseline.toString())
        val merged = FileManager.updateL4State(root) { current ->
            val keys = LinkedHashSet<String>()
            before.keys().forEach { keys.add(it) }
            desired.keys().forEach { keys.add(it) }
            keys.forEach { key ->
                val beforeHas = before.has(key)
                val desiredHas = desired.has(key)
                val changed = beforeHas != desiredHas ||
                    (beforeHas && jsonValueSignature(before.opt(key)) != jsonValueSignature(desired.opt(key)))
                if (changed) {
                    if (desiredHas) current.put(key, desired.opt(key)) else current.remove(key)
                }
            }
        }
        replaceJsonObject(state, merged)
        replaceJsonObject(baseline, merged)
    }

    private fun jsonValueSignature(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject, is JSONArray -> value.toString()
        else -> value.toString()
    }

    private fun replaceJsonObject(target: JSONObject, source: JSONObject) {
        target.keys().asSequence().toList().forEach { target.remove(it) }
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            target.put(key, source.opt(key))
        }
    }

    private fun readJson(root: File, path: String): JSONObject =
        try { JSONObject(FileManager.read(root, path)) } catch (_: Exception) { JSONObject() }

    private fun appendEvent(root: File, type: String, data: JSONObject) {
        FileManager.appendEvent(
            root,
            JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("type", type)
                .put("source", "cognition")
                .put("data", data)
        )
    }

    private fun appendChatMessage(root: File, who: String, text: String, ts: Long, sourceUrls: JSONArray = JSONArray(), routeId: String = ""): String {
        val id = java.util.UUID.randomUUID().toString()
        val event = JSONObject().put("id", id).put("ts", ts).put("type", "message")
            .put("source", if (who == "ruri") "agent" else "commander")
            .put("who", who).put("text", text)
        if (routeId.isNotBlank()) event.put("route", routeId)
        if (sourceUrls.length() > 0) event.put("source_urls", sourceUrls)
        val saved = JSONObject(FileManager.appendEvent(root, event))
        val savedId = saved.optString("id").ifBlank { id }
        if (who == "ruri") Receipts.delivered(root, savedId)
        return savedId
    }

    private fun hoursSince(ts: Long, now: Long): Double = if (ts <= 0L) -1.0 else (now - ts) / 3600000.0

    private fun energyFor(): Int {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when { hour in 1..7 -> 3; hour in 8..10 -> 6; hour in 11..18 -> 7; else -> 5 }
    }

}