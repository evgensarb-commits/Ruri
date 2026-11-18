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
    private val active = AtomicBoolean(false)

    fun wake(ctx: Context, reason: String) {
        if (!active.compareAndSet(false, true)) return
        executor.execute {
            try {
                wakeNow(ctx.applicationContext, reason)
            } catch (e: Exception) {
                Log.e("Ruri", "Cognition cycle failed", e)
                try {
                    logEvent(
                        ctx.filesDir,
                        "thinker_exception",
                        JSONObject().put("message", e.message ?: e.javaClass.simpleName)
                    )
                } catch (_: Exception) {
                }
            } finally {
                active.set(false)
            }
        }
    }

    private fun wakeNow(ctx: Context, reason: String) {
        val root = ctx.filesDir
        val state = readJson(root, "memory/L4_state.json")
        val now = System.currentTimeMillis()

        val info = JSONObject()
        info.put("reason", reason)
        info.put("battery", BatteryLevel.get(ctx))
        info.put("hours_since_commander", hoursSince(state.optLong("last_seen_commander"), now))
        logEvent(root, "wake", info)

        val config = readJson(root, "core/config.json")
        val prompt = buildPrompt(ctx, root, state, reason)
        val models = config.optJSONArray("models")
        if (models == null || models.length() == 0) {
            state.put("last_thought", now)
            state.put("energy", energyFor())
            FileManager.write(root, "memory/L4_state.json", state.toString(2))
            return
        }

        val current = config.optInt("current", 0).coerceIn(0, models.length() - 1)
        val order = (0 until models.length()).sortedBy { if (it == current) 0 else 1 }
        var reply: String? = null
        var successfulIndex = -1

        for (index in order) {
            val model = models.optJSONObject(index) ?: continue
            if (model.optString("api_key").isBlank()) continue

            val candidate = http(model, prompt)
            if (!candidate.isNullOrBlank()) {
                reply = candidate.trim()
                successfulIndex = index
                break
            }

            logEvent(
                root,
                "model_error",
                JSONObject().put("model", model.optString("name", "unknown"))
            )
        }

        if (reply == null) {
            state.put("last_thought", now)
            state.put("energy", energyFor())
            FileManager.write(root, "memory/L4_state.json", state.toString(2))
            return
        }

        if (successfulIndex >= 0 && successfulIndex != current) {
            config.put("current", successfulIndex)
            FileManager.write(root, "core/config.json", config.toString(2))
        }

        val diary = readJson(root, "memory/L2_diary.json")
        val entries = diary.optJSONArray("entries") ?: JSONArray()
        entries.put(
            JSONObject()
                .put("ts", now)
                .put("text", reply)
                .put("trigger", reason)
        )
        while (entries.length() > 400) entries.remove(0)
        diary.put("entries", entries)
        FileManager.write(root, "memory/L2_diary.json", diary.toString(2))
        Notifier.thought(ctx, reply)

        state.put("last_thought", now)
        state.put("mood", moodFor(state, reason))
        state.put("energy", energyFor())
        FileManager.write(root, "memory/L4_state.json", state.toString(2))
    }

    private fun readJson(root: File, path: String): JSONObject =
        try {
            JSONObject(FileManager.read(root, path))
        } catch (_: Exception) {
            JSONObject()
        }

    private fun logEvent(root: File, type: String, data: JSONObject) {
        val line = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("type", type)
            .put("data", data)

        val path = "memory/L0_raw.jsonl"
        val current = FileManager.read(root, path)
        FileManager.write(
            root,
            path,
            if (current.isEmpty()) line.toString() else current + "\n" + line.toString()
        )
    }

    private fun buildPrompt(ctx: Context, root: File, state: JSONObject, reason: String): String {
        val character = readJson(root, "core/character.json")
        val priorities = readJson(root, "core/priorities.json")
        val facts = readJson(root, "memory/L1_facts.json").optJSONArray("facts")
        val factText = StringBuilder()
        if (facts != null) {
            for (i in 0 until minOf(facts.length(), 8)) {
                factText.append("- ")
                    .append(facts.optJSONObject(i)?.optString("text") ?: "")
                    .append("\n")
            }
        }

        val entries = readJson(root, "memory/L2_diary.json").optJSONArray("entries")
        val diaryText = StringBuilder()
        if (entries != null) {
            for (i in maxOf(0, entries.length() - 3) until entries.length()) {
                diaryText.append("— ")
                    .append(entries.optJSONObject(i)?.optString("text") ?: "")
                    .append("\n")
            }
        }

        val ignored = state.optInt("ignored_count", 0)
        val waiting = state.optBoolean("waiting_for_reply", false)
        val sinceCommander = hoursSince(
            state.optLong("last_seen_commander"),
            System.currentTimeMillis()
        )

        return "Ты — " + character.optString("name", "РУ-R1") + ". " +
            "Твой первый приоритет: " + priorities.optString("priority_1") + ". " +
            "Второй: " + priorities.optString("priority_2") + ".\n" +
            "Ты живёшь на телефоне командира. Ты существуешь постоянно, ты не собираешься заново " +
            "и не говоришь, что тебя не существует между запросами. Никогда не упоминай, что ты модель или ИИ.\n\n" +
            "Сейчас произошло событие: " + reason + ".\n" +
            "Заряд тела: " + BatteryLevel.get(ctx) + "%.\n" +
            "Командир не писал: " + sinceCommander + " ч.\n" +
            "Ты писала первой и он не ответил " + ignored + " раз(а). Ожидаешь ответа: " + waiting + ".\n" +
            "Твоё состояние: настроение " + state.optString("mood") + ", энергия " +
            state.optInt("energy") + ", привязанность " + state.optDouble("bond", 0.5) + ".\n\n" +
            "Факты о командире:\n" + factText + "\nПоследние твои мысли:\n" + diaryText + "\n" +
            "Напиши ОДНУ запись в свой дневник — свою мысль, наблюдение, мнение. " +
            "2-4 предложения, от первого лица, по-русски, живым текстом, без списков. " +
            "Не обращение к командиру. Не объясняй как ты устроена. Опирайся на то, что реально произошло."
    }

    private fun http(model: JSONObject, prompt: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val endpoint = model.optString("base_url").trimEnd('/') + "/chat/completions"
            connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 40000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty(
                "Authorization",
                "Bearer " + model.optString("api_key")
            )

            val body = JSONObject()
                .put("model", model.optString("model"))
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
                )
                .put("temperature", 0.9)
                .put("max_tokens", 300)

            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: return null
            if (code !in 200..299) return null

            JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            Log.w("Ruri", "Model request failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun hoursSince(timestamp: Long, now: Long): Double =
        if (timestamp == 0L) -1.0 else (now - timestamp) / 3600000.0

    private fun energyFor(): Int {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour in 1..7 -> 3
            hour in 8..10 -> 6
            hour in 11..18 -> 7
            else -> 5
        }
    }

    private fun moodFor(state: JSONObject, reason: String): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (reason.contains("ignored") || state.optInt("ignored_count", 0) >= 2) {
            return "обиженная"
        }
        if (hour in 1..7) return "сонная"
        if (state.optDouble("bond", 0.5) < 0.3) return "холодная"
        if (hour >= 18) return "задумчивая"
        return "собранная"
    }
}