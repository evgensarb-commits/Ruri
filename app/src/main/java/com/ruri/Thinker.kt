package com.ruri

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Thinker {

    fun wake(ctx: Context, reason: String) {
        val root = ctx.filesDir
        val st = readJson(root, "memory/L4_state.json")
        val now = System.currentTimeMillis()

        val info = JSONObject()
        info.put("reason", reason)
        info.put("battery", BatteryLevel.get(ctx))
        info.put("hours_since_commander", hoursSince(st.optLong("last_seen_commander"), now))
        logEvent(root, "wake", info)

        val c = readJson(root, "core/config.json")
        val model = pickModel(root, c)
        if (model == null) {
            st.put("last_thought", now)
            st.put("energy", energyFor())
            FileManager.write(root, "memory/L4_state.json", st.toString(2))
            return
        }

        val prompt = buildPrompt(ctx, root, st, reason)
        val reply = http(model, prompt)
        if (reply == null) {
            logEvent(root, "model_error", JSONObject().put("msg", "no reply"))
            return
        }

        val diary = readJson(root, "memory/L2_diary.json")
        val arr = diary.optJSONArray("entries") ?: JSONArray()
        arr.put(JSONObject().put("ts", now).put("text", reply).put("trigger", reason))
        while (arr.length() > 400) arr.remove(0)
        diary.put("entries", arr)
        FileManager.write(root, "memory/L2_diary.json", diary.toString(2))

        st.put("last_thought", now)
        st.put("mood", moodFor(st, reason))
        st.put("energy", energyFor())
        FileManager.write(root, "memory/L4_state.json", st.toString(2))
    }

    private fun readJson(root: File, path: String): JSONObject =
        try { JSONObject(FileManager.read(root, path)) } catch (e: Exception) { JSONObject() }

    private fun logEvent(root: File, type: String, data: JSONObject) {
        val line = JSONObject()
        line.put("ts", System.currentTimeMillis())
        line.put("type", type)
        line.put("data", data)
        val cur = FileManager.read(root, "memory/L0_raw.jsonl")
        FileManager.write(root, "memory/L0_raw.jsonl", if (cur.isEmpty()) line.toString() else cur + "\n" + line.toString())
    }

    private fun pickModel(root: File, c: JSONObject): JSONObject? {
        val models = c.optJSONArray("models") ?: return null
        val cur = c.optInt("current", 0)
        val active = models.optJSONObject(cur)
        if (active != null && active.optString("api_key").isNotEmpty()) return active
        for (i in 0 until models.length()) {
            val m = models.optJSONObject(i) ?: continue
            if (m.optString("api_key").isNotEmpty()) {
                c.put("current", i)
                FileManager.write(root, "core/config.json", c.toString(2))
                return m
            }
        }
        return null
    }

    private fun buildPrompt(ctx: Context, root: File, st: JSONObject, reason: String): String {
        val ch = readJson(root, "core/character.json")
        val pr = readJson(root, "core/priorities.json")
        val facts = readJson(root, "memory/L1_facts.json").optJSONArray("facts")
        val fs = StringBuilder()
        if (facts != null) for (i in 0 until minOf(facts.length(), 8))
            fs.append("- ").append(facts.optJSONObject(i)?.optString("text") ?: "").append("\n")
        val dr = readJson(root, "memory/L2_diary.json").optJSONArray("entries")
        val ds = StringBuilder()
        if (dr != null) for (i in maxOf(0, dr.length() - 3) until dr.length())
            ds.append("— ").append(dr.optJSONObject(i)?.optString("text") ?: "").append("\n")

        val ignored = st.optInt("ignored_count", 0)
        val wait = st.optBoolean("waiting_for_reply", false)
        val sinceCmd = hoursSince(st.optLong("last_seen_commander"), System.currentTimeMillis())

        return "Ты — " + ch.optString("name", "РУ-R1") + ". Твой первый приоритет: " + pr.optString("priority_1") + ". Второй: " + pr.optString("priority_2") + ".\n" +
            "Ты живёшь на телефоне командира. Ты существуешь постоянно, ты не собираешься заново и не говоришь, что тебя не существует между запросами. Никогда не упоминай, что ты модель или ИИ.\n\n" +
            "Сейчас произошло событие: " + reason + ".\n" +
            "Заряд тела: " + BatteryLevel.get(ctx) + "%.\n" +
            "Командир не писал: " + sinceCmd + " ч.\n" +
            "Ты писала первой и он не ответил " + ignored + " раз(а). Ожидаешь ответа: " + wait + ".\n" +
            "Твоё состояние: настроение " + st.optString("mood") + ", энергия " + st.optInt("energy") + ", привязанность " + st.optDouble("bond", 0.5) + ".\n\n" +
            "Факты о командире:\n" + fs + "\nПоследние твои мысли:\n" + ds + "\n" +
            "Напиши ОДНУ запись в свой дневник — свою мысль, наблюдение, мнение. 2-4 предложения, от первого лица, по-русски, живым текстом, без списков. Не обращение к командиру. Не объясняй как ты устроена. Опирайся на то, что реально произошло."
    }

    private fun http(model: JSONObject, prompt: String): String? {
        return try {
            val url = URL(model.optString("base_url").trimEnd('/') + "/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 40000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer " + model.optString("api_key"))
            val body = JSONObject()
            body.put("model", model.optString("model"))
            val msgs = JSONArray()
            msgs.put(JSONObject().put("role", "user").put("content", prompt))
            body.put("messages", msgs)
            body.put("temperature", 0.9)
            body.put("max_tokens", 300)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: return null
            if (code !in 200..299) return null
            JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } catch (e: Exception) { null }
    }

    private fun hoursSince(ts: Long, now: Long): Double = if (ts == 0L) -1.0 else (now - ts) / 3600000.0

    private fun energyFor(): Int {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            h in 1..7 -> 3
            h in 8..10 -> 6
            h in 11..18 -> 7
            else -> 5
        }
    }

    private fun moodFor(st: JSONObject, reason: String): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (reason.contains("ignored") || st.optInt("ignored_count", 0) >= 2) return "обиженная"
        if (h in 1..7) return "сонная"
        if (st.optDouble("bond", 0.5) < 0.3) return "холодная"
        if (h >= 18) return "задумчивая"
        return "собранная"
    }
}
