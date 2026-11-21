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

        appendEvent(root, "wake", JSONObject()
            .put("reason", reason)
            .put("battery", BatteryLevel.get(ctx))
            .put("hours_since_commander", hoursQuiet))

        if (!NetState.online) {
            // Local state and memory maintenance already ran in RuriLifeWorker.
            state.put("last_thought", now)
            persistStatePreservingTurnInFlight(root, state)
            return
        }

        val config = try { JSONObject(SecretStore.readRuntimeConfig(root)) } catch (e: Exception) {
            Log.e("Ruri", "Could not load model configuration", e)
            readJson(root, "core/config.json")
        }
        val models = config.optJSONArray("models") ?: JSONArray()
        if (models.length() == 0) {
            state.put("last_thought", now)
            persistStatePreservingTurnInFlight(root, state)
            return
        }

        val prompt = buildPrompt(ctx, root, state, reason, initiative)
        val current = config.optInt("current", 0)
        val task = if (initiative) Task.WARMTH else Task.REASON
        val order = FreeModelRouter.orderedIndices(root, models, current, task)
        if (order.isEmpty()) {
            state.put("last_thought", now)
            persistStatePreservingTurnInFlight(root, state)
            return
        }
        var reply: String? = null
        var successfulIndex = -1
        var successfulRouteId = ""
        for (index in order) {
            val model = models.optJSONObject(index) ?: continue
            // All cognition routes must carry the full memory context.
            if (model.optString("api_key").isBlank()) continue
            val routeId = model.optString("id").ifBlank { model.optString("name", "route_$index") }
            runCatching { RouteMeta.recordAttempt(root, routeId) }
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", prompt))
                .put(JSONObject().put("role", "user").put("content",
                    if (initiative) "Напиши сообщение Командиру обычной прозой." else "Напиши внутреннюю мысль для дневника обычной прозой."))
            val initial = requestModel(model, messages, root, routeId)
            reply = if (initial.isNullOrBlank()) null else ToolLoop.unwrapDiaryReply(root, initial, messages) { next ->
                ChatEngine.requestToolFollowup(model, next)
            }
            if (!reply.isNullOrBlank() && initiative &&
                RouteSelector.looksLikeRefusalOrDisclaimer(reply)) {
                runCatching { RouteMeta.recordFailure(root, routeId, "refusal", task) }
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
                runCatching { RouteMeta.recordSuccess(root, routeId, task) }
                successfulIndex = index
                successfulRouteId = routeId
                break
            }
            runCatching { RouteMeta.recordFailure(root, routeId, "other", task) }
            appendEvent(root, "model_error", JSONObject().put("model", model.optString("name", "unknown")))
        }

        if (reply.isNullOrBlank()) {
            state.put("last_thought", now)
            persistStatePreservingTurnInFlight(root, state)
            return
        }
        if (successfulIndex >= 0 && successfulIndex != current) {
            config.put("current", successfulIndex)
            SecretStore.saveRuntimeConfig(root, config.toString(2))
        }
        val (cleanedReply, emotionDelta) = EmotionEngine.parseAndStrip(reply)
        val emotion = if (emotionDelta != null) EmotionEngine.applyDelta(root, emotionDelta, "thought") else null
        val (cleanThought, diaryTexts, _) = ToolLoop.stripActions(cleanedReply)
        val diaryText = cleanThought.ifBlank { diaryTexts.firstOrNull().orEmpty() }
        val safeReply = SecretRedactor.redactText(diaryText.trim())
        if (safeReply.isBlank() || ToolLoop.isActionJson(safeReply)) {
            state.put("last_thought", now)
            persistStatePreservingTurnInFlight(root, state)
            return
        }

        Diary.append(root, safeReply, reason, emotion, if (initiative) "said" else "thought")
        val diaryEntryId = runCatching {
            val entries = readJson(root, "memory/L2_diary.json").optJSONArray("entries") ?: JSONArray()
            entries.optJSONObject(entries.length() - 1)?.optString("id")
        }.getOrNull().orEmpty()
        val notificationMessageId = if (initiative) {
            appendChatMessage(root, "ruri", safeReply, now, ChatEngine.sourceUrlsSnapshot(), successfulRouteId)
        } else diaryEntryId
        Receipts.delivered(root, notificationMessageId)
        if (initiative) {
            state.put("waiting_for_reply", true)
            state.put("initiative_sent_at", now)
            state.put("last_initiative", now)
        }
        state.put("last_thought", now)
        state.put("mood", EmotionEngine.moodLabel(EmotionEngine.readValues(root)))
        state.put("energy", energyFor())
        persistStatePreservingTurnInFlight(root, state)
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
        if (facts != null) for (i in maxOf(0, facts.length() - 12) until facts.length()) {
            factText.append("- ").append(facts.optJSONObject(i)?.optString("text") ?: "").append("\n")
        }
        val diary = readJson(root, "memory/L2_diary.json").optJSONArray("entries")
        val diaryText = StringBuilder()
        if (diary != null) for (i in maxOf(0, diary.length() - 5) until diary.length()) {
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
        val recalledMemories = MemoryRetriever.recall(root, recallQuery, limit = 5)
        val shelfNames = try { JSONArray(FileManager.list(root, "shelf")) } catch (_: Exception) { JSONArray() }
        val shelfText = StringBuilder()
        for (i in 0 until minOf(shelfNames.length(), 6)) {
            val name = shelfNames.optString(i)
            if (name.isNotBlank()) {
                shelfText.append("\n--- ").append(name).append(" ---\n")
                    .append(FileManager.read(root, "shelf/" + name).take(900))
            }
        }
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
        }.take(20).asReversed().joinToString("\n")
        val bodyEventText = FileManager.readLastLines(
            root, "memory/L0_raw.jsonl", maxLines = 1200, maxBytes = 1024 * 1024
        ).asReversed().mapNotNull { line ->
            runCatching {
                val event = JSONObject(line)
                val type = event.optString("type")
                if (type.isBlank() || type == "message" || type == "phone_notification") null
                else "- $type: " + event.optString("text").take(260)
            }.getOrNull()
        }.take(12).asReversed().joinToString("\n")
        val researchText = FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 1200, maxBytes = 1024 * 1024)
            .asReversed().mapNotNull { line ->
                runCatching {
                    val event = JSONObject(line)
                    if (event.optString("type") != "web_research_result") null
                    else "Запрос: " + event.optString("query") + "\\n" + event.optString("text").take(5000)
                }.getOrNull()
            }.take(4).asReversed().joinToString("\\n\\n")
        val summary = readJson(root, "memory/L3_summary.json").optString("summary")
        val quiet = hoursSince(state.optLong("last_seen_commander"), System.currentTimeMillis())
        val common = "Имя: " + character.optString("name") +
            ". Обозначение: " + character.optString("designation") + ". Личность: " + character.optString("persona") +
            ". Правила речи: " + character.optString("speech_rules") +
            ". Непрерывность: " + character.optString("continuity_rules") +
            ". Длина ответа: " + character.optString("length_policy") +
            ". Эмоции: " + character.optString("emotions") +
            ". Регуляторы: " + (character.optJSONArray("regulators")?.toString() ?: "[]") +
            ". Запрещённые фразы: " + (character.optJSONArray("banned_phrases")?.toString() ?: "[]") + ".\n" +
            Capabilities.bodySection(root) + "\n" +
            EmotionEngine.promptSection(root) + "\n" +
            "Первый приоритет: " + priorities.optString("priority_1", "Жить") +
            ". Второй: " + priorities.optString("priority_2", "Любить Командира") + ".\n" +
            "Конституция: " + (constitution.optJSONArray("primary_needs")?.toString() ?: "[]") + ". Обе главные потребности равнозначны и неизменны.\n" +
            "Событие: " + reason + ". Заряд: " + BatteryLevel.get(ctx) + "%. Тишина Командира: " + quiet + " ч. Игнорирований инициативы: " + state.optInt("ignored_count", 0) + ".\n" +
            "Состояние: " + state.optString("mood", "собранная") + ", энергия " + state.optInt("energy", 5) + ", привязанность " + state.optDouble("bond", 0.5) + ".\n" +
            "Драйвы: " + (drives.optJSONArray("drives")?.toString() ?: "[]") + ". Текущий фокус: " + state.optString("current_focus", selfModel.optString("current_focus")) + ".\n" +
            "Активные цели: " + (goals.optJSONArray("active")?.toString() ?: "[]") + ".\n" +
            "Незавершённые задачи, которые нужно продолжить: " + (state.optJSONArray("active_tasks")?.toString() ?: "[]") + ".\n" +
            "Воспоминания, найденные по ассоциациям: " + (if (recalledMemories.isBlank()) "подходящих записей не найдено" else "\n" + recalledMemories) + "\n" +
            "Ниже — ТВОЯ ДОЛГОСРОЧНАЯ ПАМЯТЬ. Это не контекст одного диалога; ты помнишь между сессиями.\n" +
            "ТВОИ ФАКТЫ (L1):\n" + factText + "\n" +
            "ТВОЙ ДНЕВНИК (L2):\n" + diaryText + "\n" +
            "ТВОЯ АВТОБИОГРАФИЯ (L3):\n" + summary + "\n" +
            "ПОСЛЕДНИЕ СОБЫТИЯ ТВОЕГО ТЕЛА (L0):\n" + bodyEventText + "\n" + notificationText + "\nМатериалы с полки:\n" + shelfText + "\n"
        val prompt = if (initiative) common +
            "\nСейчас ты сама решила написать Командиру после долгой тишины. Выбери форму и объём сообщения по полю length_policy и текущему настроению."
        else common +
            "\nНапиши запись в дневник от первого лица, по-русски. Это внутренняя мысль, наблюдение или вывод, а не обращение к Командиру. Объём выбери по length_policy. Не выдумывай события."
        return SecretRedactor.redactText(prompt)
    }

    private fun requestModel(model: JSONObject, messages: JSONArray, root: File, routeId: String): String? {
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
                .put("temperature", 0.85)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: return null
            if (code !in 200..299) return null
            val response = JSONObject(text)
            val usage = response.optJSONObject("usage")
            val tokens = usage?.optLong("total_tokens", -1L)?.takeIf { it >= 0L }
                ?: ((usage?.optLong("prompt_tokens", 0L) ?: 0L) + (usage?.optLong("completion_tokens", 0L) ?: 0L))
            if (tokens > 0) RouteMeta.addTokens(root, routeId, tokens)
            response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } catch (e: Exception) {
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

    private fun persistStatePreservingTurnInFlight(root: File, state: JSONObject) {
        state.put("turn_in_flight", readJson(root, "memory/L4_state.json")
            .optBoolean("turn_in_flight", false))
        FileManager.write(root, "memory/L4_state.json", state.toString(2))
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