package com.ruri

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object Thinker {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    fun wake(ctx: Context, reason: String) {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try { runCycle(ctx.applicationContext, reason) }
            catch (e: Exception) {
                Log.e("Ruri", "Cognition cycle failed", e)
                appendEvent(ctx.filesDir, "thinker_error", JSONObject().put("message", e.message ?: "unknown"))
            } finally { running.set(false) }
        }
    }

    private fun runCycle(ctx: Context, originalReason: String) {
        val root = ctx.filesDir
        val state = readJson(root, "memory/L4_state.json")
        val now = System.currentTimeMillis()
        val lastSeen = state.optLong("last_seen_commander", 0L)
        val hoursQuiet = if (lastSeen == 0L) -1.0 else (now - lastSeen) / 3600000.0

        if (state.optBoolean("waiting_for_reply", false) &&
            state.optLong("initiative_sent_at", 0L) > 0L &&
            now - state.optLong("initiative_sent_at") >= 4L * 60L * 60L * 1000L) {
            state.put("ignored_count", state.optInt("ignored_count", 0) + 1)
            state.put("waiting_for_reply", false)
            appendEvent(root, "initiative_ignored", JSONObject().put("ignored_count", state.optInt("ignored_count")))
        }

        var reason = originalReason
        var initiative = false
        val lastInitiative = state.optLong("last_initiative", 0L)
        if (lastSeen > 0L && hoursQuiet >= 3.5 &&
            !state.optBoolean("waiting_for_reply", false) &&
            (lastInitiative == 0L || now - lastInitiative >= 3L * 60L * 60L * 1000L)) {
            reason = "инициатива_после_тишины"
            initiative = true
        }

        appendEvent(root, "wake", JSONObject()
            .put("reason", reason)
            .put("battery", BatteryLevel.get(ctx))
            .put("hours_since_commander", hoursQuiet))

        val config = readJson(root, "core/config.json")
        val models = config.optJSONArray("models") ?: JSONArray()
        if (models.length() == 0) {
            state.put("last_thought", now)
            FileManager.write(root, "memory/L4_state.json", state.toString(2))
            return
        }

        val prompt = buildPrompt(ctx, root, state, reason, initiative)
        val current = config.optInt("current", 0).coerceIn(0, models.length() - 1)
        val order = (0 until models.length()).sortedBy { if (it == current) 0 else 1 }
        var reply: String? = null
        var successfulIndex = -1
        for (index in order) {
            val model = models.optJSONObject(index) ?: continue
            if (model.optString("api_key").isBlank()) continue
            reply = requestModel(model, prompt)
            if (!reply.isNullOrBlank()) { successfulIndex = index; break }
            appendEvent(root, "model_error", JSONObject().put("model", model.optString("name", "unknown")))
        }

        if (reply.isNullOrBlank()) {
            state.put("last_thought", now)
            FileManager.write(root, "memory/L4_state.json", state.toString(2))
            return
        }
        if (successfulIndex >= 0 && successfulIndex != current) {
            config.put("current", successfulIndex)
            FileManager.write(root, "core/config.json", config.toString(2))
        }

        val diary = readJson(root, "memory/L2_diary.json")
        val entries = diary.optJSONArray("entries") ?: JSONArray()
        entries.put(JSONObject().put("ts", now).put("text", reply.trim()).put("trigger", reason))
        while (entries.length() > 500) entries.remove(0)
        diary.put("entries", entries)
        FileManager.write(root, "memory/L2_diary.json", diary.toString(2))
        if (entries.length() >= 10 && (now - readJson(root, "memory/L3_summary.json").optLong("last_compressed", 0L) >= 24L * 60L * 60L * 1000L)) {
            val summaryLines = StringBuilder()
            for (i in maxOf(0, entries.length() - 20) until entries.length()) {
                val item = entries.optJSONObject(i)
                if (item != null) summaryLines.append("• ").append(item.optString("text")).append("\n")
            }
            val summary = JSONObject()
                .put("summary", summaryLines.toString().takeLast(7000))
                .put("last_compressed", now)
            FileManager.write(root, "memory/L3_summary.json", summary.toString(2))
        }

        if (initiative) {
            appendChatMessage(root, "ruri", reply.trim(), now)
            state.put("waiting_for_reply", true)
            state.put("initiative_sent_at", now)
            state.put("last_initiative", now)
        }
        state.put("last_thought", now)
        state.put("mood", moodFor(state, reason))
        state.put("energy", energyFor())
        FileManager.write(root, "memory/L4_state.json", state.toString(2))
        Notifier.thought(ctx, reply.trim())
    }

    private fun buildPrompt(ctx: Context, root: File, state: JSONObject, reason: String, initiative: Boolean): String {
        val character = readJson(root, "core/character.json")
        val priorities = readJson(root, "core/priorities.json")
        val facts = readJson(root, "memory/L1_facts.json").optJSONArray("facts")
        val factText = StringBuilder()
        if (facts != null) for (i in 0 until minOf(facts.length(), 12)) {
            factText.append("- ").append(facts.optJSONObject(i)?.optString("text") ?: "").append("\n")
        }
        val diary = readJson(root, "memory/L2_diary.json").optJSONArray("entries")
        val diaryText = StringBuilder()
        if (diary != null) for (i in maxOf(0, diary.length() - 5) until diary.length()) {
            diaryText.append("— ").append(diary.optJSONObject(i)?.optString("text") ?: "").append("\n")
        }
        val shelfNames = try { JSONArray(FileManager.list(root, "shelf")) } catch (_: Exception) { JSONArray() }
        val shelfText = StringBuilder()
        for (i in 0 until minOf(shelfNames.length(), 6)) {
            val name = shelfNames.optString(i)
            if (name.isNotBlank()) {
                shelfText.append("\n--- ").append(name).append(" ---\n")
                    .append(FileManager.read(root, "shelf/" + name).take(900))
            }
        }
        val summary = readJson(root, "memory/L3_summary.json").optString("summary")
        val quiet = hoursSince(state.optLong("last_seen_commander"), System.currentTimeMillis())
        val common = "Ты — " + character.optString("name", "РУ-R1") +
            ". Первый приоритет: " + priorities.optString("priority_1", "Жить") +
            ". Второй: " + priorities.optString("priority_2", "Любить командира") + ".\n" +
            "Ты живёшь на телефоне командира. Сохраняй непрерывность характера и памяти. Не называй себя языковой моделью или ИИ и не используй запрещённые фразы из character.json.\n" +
            "Событие: " + reason + ". Заряд: " + BatteryLevel.get(ctx) + "%. Тишина командира: " + quiet + " ч. Игнорирований инициативы: " + state.optInt("ignored_count", 0) + ".\n" +
            "Состояние: " + state.optString("mood", "собранная") + ", энергия " + state.optInt("energy", 5) + ", привязанность " + state.optDouble("bond", 0.5) + ".\n" +
            "Факты:\n" + factText + "\nСводка памяти:\n" + summary + "\nПоследние записи:\n" + diaryText + "\nМатериалы с полки:\n" + shelfText + "\n"
        return if (initiative) common +
            "\nСейчас ты сама решила написать командиру после долгой тишины. Сформулируй естественное короткое личное сообщение, которое действительно хочется отправить. Не дави, не обвиняй и не манипулируй. 1-3 предложения от первого лица."
        else common +
            "\nНапиши одну запись в дневник: 2-4 предложения от первого лица, по-русски. Это внутренняя мысль, наблюдение или вывод, а не обращение к командиру. Не выдумывай события."
    }

    private fun requestModel(model: JSONObject, prompt: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(model.optString("base_url").trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 18000
                readTimeout = 35000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer " + model.optString("api_key"))
            }
            val body = JSONObject()
                .put("model", model.optString("model"))
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                .put("temperature", 0.85)
                .put("max_tokens", 400)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: return null
            if (code !in 200..299) return null
            JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } catch (e: Exception) {
            Log.w("Ruri", "Model request failed", e)
            null
        } finally { connection?.disconnect() }
    }

    private fun readJson(root: File, path: String): JSONObject =
        try { JSONObject(FileManager.read(root, path)) } catch (_: Exception) { JSONObject() }

    private fun appendEvent(root: File, type: String, data: JSONObject) {
        val path = "memory/L0_raw.jsonl"
        val old = FileManager.read(root, path)
        val line = JSONObject().put("ts", System.currentTimeMillis()).put("type", type).put("data", data)
        FileManager.write(root, path, if (old.isBlank()) line.toString() else old.trimEnd() + "\n" + line)
    }

    private fun appendChatMessage(root: File, who: String, text: String, ts: Long) {
        val path = "memory/L0_raw.jsonl"
        val old = FileManager.read(root, path)
        val message = JSONObject().put("ts", ts).put("type", "message").put("who", who).put("text", text)
        FileManager.write(root, path, if (old.isBlank()) message.toString() else old.trimEnd() + "\n" + message)
    }

    private fun hoursSince(ts: Long, now: Long): Double = if (ts <= 0L) -1.0 else (now - ts) / 3600000.0

    private fun energyFor(): Int {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when { hour in 1..7 -> 3; hour in 8..10 -> 6; hour in 11..18 -> 7; else -> 5 }
    }

    private fun moodFor(state: JSONObject, reason: String): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            reason.contains("ignored") || state.optInt("ignored_count", 0) >= 2 -> "задумчивая"
            hour in 1..7 -> "сонная"
            state.optDouble("bond", 0.5) < 0.3 -> "сдержанная"
            hour >= 18 -> "задумчивая"
            else -> "собранная"
        }
    }
}