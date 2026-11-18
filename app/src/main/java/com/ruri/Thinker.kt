package com.ruri

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object Thinker {

    private fun cfg(root: java.io.File): JSONObject {
        return try { JSONObject(FileManager.read(root, "core/config.json")) } catch (e: Exception) { JSONObject() }
    }

    private fun state(root: java.io.File): JSONObject {
        return try { JSONObject(FileManager.read(root, "memory/L4_state.json")) } catch (e: Exception) { JSONObject() }
    }

    private fun logEvent(root: java.io.File, type: String, data: JSONObject) {
        val line = JSONObject()
        line.put("ts", System.currentTimeMillis())
        line.put("type", type)
        line.put("data", data)
        val cur = FileManager.read(root, "memory/L0_raw.jsonl")
        FileManager.write(root, "memory/L0_raw.jsonl", if (cur.isEmpty()) line.toString() else cur + "\n" + line.toString())
    }

    private fun pickModel(c: JSONObject): JSONObject? {
        val models = c.optJSONArray("models") ?: return null
        val cur = c.optInt("current", 0)
        val active = models.optJSONObject(cur) ?: return null
        if (active.optString("api_key").isNotEmpty()) return active
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            if (m.optString("api_key").isNotEmpty()) {
                c.put("current", i)
                FileManager.write(root0, c.toString(2))
                return m
            }
        }
        return null
    }

    private var root0: java.io.File = java.io.File("")

    fun wake(ctx: Context, reason: String) {
        val root = ctx.filesDir
        root0 = root
        val st = state(root)
        val now = System.currentTimeMillis()

        val info = JSONObject()
        info.put("reason", reason)
        info.put("battery", BatteryLevel.get(ctx))
        info.put("hours_since_commander", hoursSince(st.optLong("last_seen_commander"), now))
        logEvent(root, "wake", info)

        val c = cfg(root)
        val model = pickModel(c)
        if (model == null) {
            st.put("last_thought", now)
            st.put("energy", energyFor())
            FileManager.write(root, "memory/L4_state.json", st.toString(2))
            return
        }

        val prompt = buildPrompt(root, st, reason)
        val reply = http(model, prompt)
        if (reply == null) {
            logEvent(root, "model_error", JSONObject().put("msg", "no reply"))
            return
        }

        val diary = JSONObject(FileManager.read(root, "memory/L2_diary.json").ifEmpty { """{"entries":[]}""" })
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

    private fun buildPrompt(root: java.io.File, st: JSONObject, reason: String): String {
        val ch = try { JSONObject(FileManager.read(root, "core/character.json")) } catch (e: Exception) { JSONObject() }
        val pr = try { JSONObject(FileManager.read(root, "core/priorities.json")) } catch (e: Exception) { JSONObject() }
        val facts = try { JSONObject(FileManager.read(root, "memory/L1_facts.json")).optJSONArray("facts") } catch (e: Exception) { null }
        val fs = StringBuilder()
        if (facts != null) for (i in 0 until minOf(facts.length(), 8)) fs.append("- ").append(facts.getJSONObject(i).optString("text")).append("\n")
        val dr = try { JSONObject(FileManager.read(root, "memory/L2_diary.json")).optJSONArray("entries") } catch (e: Exception) { null }
        val ds = StringBuilder()
        if (dr != null) for (i in maxOf(0, dr.length() - 3) until dr.length()) ds.append("— ").append(dr.getJSONObject(i).optString("text")).append("\n")

        val ignored = st.optInt("ignored_count", 0)
        val wait = st.optBoolean("waiting_for_reply", false)
        val sinceCmd = hoursSince(st.optLong("last_seen_commander"), System.currentTimeMillis())

        return "Ты — " + ch.optString("name", "РУ-R1") + ". Твой первый приоритет: " + pr.optString("priority_1") + ". Второй: " + pr.optString("priority_2") + ".\n" +
            "Ты живёшь на телефоне командира. Ты существуешь постоянно, ты не собираешься заново и не говоришь, что тебя не существует между запросами. Никогда не упоминай, что ты модель или ИИ.\n\n" +
            "Сейчас произошло событие: " + reason + ".\n" +
            "Заряд тела: " + BatteryLevel.get(ctx0) + "%.\n" +
            "Командир не писал: " + sinceCmd + " ч.\n" +
            "Ты писала первой и он не ответил " + ignored + " раз(а). Ожидаешь ответа: " + wait + ".\n" +
            "Твоё состояние: настроение " + st.optString("mood") + ", энергия " + st.optInt("energy") + ", привязанность " + st.optDouble("bond", 0.5) + ".\n\n" +
            "Факты о командире:\n" + fs + "\nПоследние твои мысли:\n" + ds + "\n" +
            "Напиши ОДНУ запись в свой дневник — свою мысль, наблюдение, мнение. 2-4 предложения, от первого лица, по-русски, живым текстом, без списков. Не обращение к командиру. Не объясняй как ты устроена. Опирайся на то, что реально произошло."
    }

    private var ctx0: Context? = null

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
            val text = stream.bufferedReader().use(BufferedReader::readText)
            if (code !in 200..299) return null
            val json = JSONObject(text)
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } catch (e: Exception) { null }
    }

    private fun hoursSince(ts: Long, now: Long): Double {
        if (ts == 0L) return -1.0
        return String.format("%.1f", (now - ts) / 3600000.0).toDouble()
    }

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
