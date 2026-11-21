package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.exp

object EmotionEngine {
    private const val CONFIG = "core/emotions.json"
    private const val STATE = "memory/emotions.json"
    private const val LN2 = 0.6931471805599453
    private const val HOUR = 3600000.0

    private fun ch(min: Int, max: Int, base: Int, halfH: Int) = JSONObject()
        .put("min", min).put("max", max).put("base", base).put("half_life_h", halfH)

    private fun defaults(): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put("channels", JSONObject()
            .put("joy", ch(0, 100, 50, 6))
            .put("affection", ch(0, 100, 50, 12))
            .put("hurt", ch(0, 100, 10, 24))
            .put("anger", ch(0, 100, 10, 3))
            .put("sadness", ch(0, 100, 20, 8))
            .put("anxiety", ch(0, 100, 20, 4))
            .put("interest", ch(0, 100, 30, 2))
            .put("boredom", ch(0, 100, 20, 1))
            .put("desire", ch(30, 100, 30, 6))
            .put("calm", ch(0, 100, 60, 6))
            .put("fatigue", ch(0, 100, 20, 4)))
        .put("drift", JSONObject()
            .put("idle_boredom_per_h", 2)
            .put("silence_anxiety_per_h_after_4h", 1)
            .put("shelf_interest", 10)
            .put("search_ok_interest", 8).put("search_ok_joy", 4)
            .put("search_fail_anger", 6).put("search_fail_anxiety", 3)
            .put("cycle_fatigue", 1)
            .put("desire_flirt_commander", 12).put("desire_intimate_word", 8)
            .put("desire_evening_per_h", 1.5).put("desire_from_affection_per_h", 1.0)
            .put("desire_nsfw_window", 10)
            .put("rest_relief_fatigue", 5).put("rest_h", 2)
            .put("ignored_hurt", 15).put("ignored_anger", 5)
            .put("reply_hurt_relief", 10).put("reply_anxiety_relief", 5).put("reply_joy", 5)
            .put("base_initiative_h", 3.5).put("base_cooldown_h", 3.0).put("base_reflection_h", 6.0)
            .put("w_hurt_init", 2.0).put("w_fatigue_init", 2.0).put("w_boredom_init", 1.5)
            .put("w_affection_init", 1.0).put("w_anxiety_init", 1.0).put("w_interest_init", 0.5))
        .put("lexicon", JSONObject()
            .put("sadness", JSONArray().put("груст").put("плохо").put("тоска").put("тяжело").put("печал"))
            .put("joy", JSONArray().put("рад").put("счаст").put("весел").put("круто").put("кайф"))
            .put("anger", JSONArray().put("зл").put("бесит").put("раздраж").put("достал"))
            .put("anxiety", JSONArray().put("тревож").put("боюсь").put("страш").put("беспоко"))
            .put("affection", JSONArray().put("люблю").put("нежн").put("скучаю").put("обним"))
            .put("desire", JSONArray().put("хочу тебя").put("томн").put("возбужд")))

    fun ensure(root: File) {
        if (!FileManager.exists(root, CONFIG)) FileManager.write(root, CONFIG, defaults().toString(2))
        val cfg = runCatching { JSONObject(FileManager.read(root, CONFIG)) }.getOrElse { defaults() }
        val defDrift = defaults().optJSONObject("drift") ?: JSONObject()
        val curDrift = cfg.optJSONObject("drift") ?: JSONObject().also { cfg.put("drift", it) }
        for (k in defDrift.keys()) if (!curDrift.has(k)) curDrift.put(k, defDrift.get(k))
        FileManager.write(root, CONFIG, cfg.toString(2))
        if (!FileManager.exists(root, STATE)) {
            val values = JSONObject()
            for ((name, c) in channels(root)) values.put(name, c.optDouble("base", 50.0))
            writeState(root, values, System.currentTimeMillis(), 0L, 0L, 0L)
        }
    }

    private fun config(root: File): JSONObject =
        runCatching { JSONObject(FileManager.read(root, CONFIG)) }.getOrElse { defaults() }

    private fun channels(root: File): Map<String, JSONObject> {
        val obj = config(root).optJSONObject("channels") ?: JSONObject()
        val map = LinkedHashMap<String, JSONObject>()
        for (k in obj.keys()) map[k] = obj.optJSONObject(k) ?: JSONObject()
        return map
    }

    private fun drift(root: File): JSONObject = config(root).optJSONObject("drift") ?: JSONObject()

    fun thresholds(root: File): JSONObject {
        val v = readValues(root)
        val d = drift(root)
        val channelConfig = config(root).optJSONObject("channels") ?: JSONObject()
        // Normalize around each channel's configured base, so a neutral state preserves
        // the historical 3.5h / 3h / 6h schedule exactly.
        fun n(k: String): Double {
            val base = channelConfig.optJSONObject(k)?.optDouble("base", 0.0) ?: 0.0
            return (v.optDouble(k, base) - base) / 100.0
        }
        val init = (d.optDouble("base_initiative_h", 3.5)
            + n("hurt") * d.optDouble("w_hurt_init", 2.0)
            + n("fatigue") * d.optDouble("w_fatigue_init", 2.0)
            - n("boredom") * d.optDouble("w_boredom_init", 1.5)
            - n("affection") * d.optDouble("w_affection_init", 1.0)
            - n("anxiety") * d.optDouble("w_anxiety_init", 1.0)
            - n("interest") * d.optDouble("w_interest_init", 0.5)).coerceIn(0.5, 8.0)
        val cool = (d.optDouble("base_cooldown_h", 3.0)
            + n("fatigue") * 2.0 + n("hurt") * 1.0
            - n("boredom") * 1.0 - n("affection") * 0.5).coerceIn(0.5, 6.0)
        val refl = (d.optDouble("base_reflection_h", 6.0)
            - n("interest") * 2.0 - n("boredom") * 2.0
            + n("fatigue") * 2.0).coerceIn(1.0, 12.0)
        return JSONObject()
            .put("initiative_h", init)
            .put("cooldown_h", cool)
            .put("reflection_h", refl)
    }

    internal fun initiativeGate(
        root: File,
        lastSeen: Long,
        now: Long,
        waitingForReply: Boolean,
        lastInitiative: Long
    ): Boolean {
        if (lastSeen <= 0L || waitingForReply) return false
        val th = thresholds(root)
        val quietHours = (now - lastSeen).coerceAtLeast(0L) / HOUR
        val cooldownMs = (th.optDouble("cooldown_h", 3.0) * HOUR).toLong()
        return quietHours >= th.optDouble("initiative_h", 3.5) &&
            (lastInitiative == 0L || now - lastInitiative >= cooldownMs)
    }

    private fun readState(root: File): JSONObject =
        runCatching { JSONObject(FileManager.read(root, STATE)) }.getOrElse { JSONObject() }

    fun readValues(root: File): JSONObject {
        val v = readState(root).optJSONObject("values")
        if (v != null) return v
        val values = JSONObject()
        for ((name, c) in channels(root)) values.put(name, c.optDouble("base", 50.0))
        return values
    }

    private fun writeState(root: File, values: JSONObject, now: Long, lastTick: Long, lastCycle: Long, lastRest: Long) {
        FileManager.write(root, STATE, JSONObject()
            .put("values", values).put("updated_at", now)
            .put("last_tick_at", lastTick).put("last_cycle_at", lastCycle)
            .put("last_rest_relief_at", lastRest).toString(2))
    }

    private fun clamp(value: Double, c: JSONObject): Double =
        value.coerceIn(c.optDouble("min", 0.0), c.optDouble("max", 100.0))

    fun snapshot(root: File): JSONObject = JSONObject(readValues(root).toString())

    fun applyDelta(root: File, delta: JSONObject, source: String): JSONObject {
        val now = System.currentTimeMillis()
        val before = readValues(root)
        val after = JSONObject(); val applied = JSONObject()
        for ((name, c) in channels(root)) {
            val cur = before.optDouble(name, c.optDouble("base", 50.0))
            val d = delta.optDouble(name, 0.0)
            val nv = if (d.isFinite() && d != 0.0) clamp(cur + d, c) else cur
            after.put(name, nv)
            if (d.isFinite() && d != 0.0) applied.put(name, d)
        }
        val st = readState(root)
        writeState(root, after, now, st.optLong("last_tick_at", now), st.optLong("last_cycle_at", 0L), st.optLong("last_rest_relief_at", 0L))
        val mood = moodLabel(after)
        if (applied.length() > 0) FileManager.appendEvent(root, JSONObject()
            .put("type", "emotion_delta").put("source", source)
            .put("delta", applied).put("after", after).put("mood", mood).put("ts", now))
        return JSONObject().put("before", before).put("delta", applied).put("after", after).put("mood", mood)
    }

    fun decay(root: File, now: Long) {
        val st = readState(root)
        val dtH = (now - st.optLong("updated_at", now)) / HOUR
        if (dtH <= 0) return
        val values = readValues(root); val out = JSONObject()
        for ((name, c) in channels(root)) {
            val base = c.optDouble("base", 50.0)
            val half = c.optDouble("half_life_h", 6.0).coerceAtLeast(0.1)
            out.put(name, clamp(base + (values.optDouble(name, base) - base) * exp(-LN2 * dtH / half), c))
        }
        writeState(root, out, now, st.optLong("last_tick_at", now), st.optLong("last_cycle_at", 0L), st.optLong("last_rest_relief_at", 0L))
    }

    fun tick(root: File, now: Long, lastSeenCommander: Long, lastEventAt: Long) {
        val st = readState(root)
        val dtH = ((now - st.optLong("last_tick_at", now)) / HOUR).coerceAtLeast(0.0)
        decay(root, now)
        val d = drift(root); val delta = JSONObject()
        if (dtH > 0) {
            delta.put("boredom", d.optDouble("idle_boredom_per_h", 2.0) * dtH)
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (hour in 20..23 || hour in 0..4) delta.put("desire", delta.optDouble("desire", 0.0) + d.optDouble("desire_evening_per_h", 1.5) * dtH)
            if (readValues(root).optDouble("affection", 0.0) > 70) delta.put("desire", delta.optDouble("desire", 0.0) + d.optDouble("desire_from_affection_per_h", 1.0) * dtH)
            val silenceH = if (lastSeenCommander > 0) (now - lastSeenCommander) / HOUR else 0.0
            if (silenceH > 4) delta.put("anxiety", d.optDouble("silence_anxiety_per_h_after_4h", 1.0) * dtH)
        }
        val lastCycle = st.optLong("last_cycle_at", 0L)
        val lastRest = st.optLong("last_rest_relief_at", 0L)
        var newRest = lastRest
        val restMs = d.optDouble("rest_h", 2.0) * HOUR
        if (lastCycle > 0 && (now - lastCycle) > restMs && (now - lastRest) > restMs) {
            delta.put("fatigue", -d.optDouble("rest_relief_fatigue", 5.0)); newRest = now
        }
        if (delta.length() > 0) applyDelta(root, delta, "life")
        val st2 = readState(root)
        writeState(root, readValues(root), now, now, st2.optLong("last_cycle_at", 0L), newRest)
    }

    fun onCycle(root: File) {
        applyDelta(root, JSONObject().put("fatigue", drift(root).optDouble("cycle_fatigue", 1.0)), "cycle")
        val st = readState(root); val now = System.currentTimeMillis()
        writeState(root, readValues(root), now, st.optLong("last_tick_at", now), now, st.optLong("last_rest_relief_at", 0L))
    }

    fun onShelfFile(root: File) =
        applyDelta(root, JSONObject().put("interest", drift(root).optDouble("shelf_interest", 10.0)), "shelf")

    fun onSearchResult(root: File, ok: Boolean) {
        val d = drift(root)
        applyDelta(root, if (ok) JSONObject().put("interest", d.optDouble("search_ok_interest", 8.0)).put("joy", d.optDouble("search_ok_joy", 4.0))
        else JSONObject().put("anger", d.optDouble("search_fail_anger", 6.0)).put("anxiety", d.optDouble("search_fail_anxiety", 3.0)), "search")
    }

    fun onIgnored(root: File) {
        val d = drift(root)
        applyDelta(root, JSONObject().put("hurt", d.optDouble("ignored_hurt", 15.0)).put("anger", d.optDouble("ignored_anger", 5.0)), "ignored")
    }

    fun onCommanderMessage(root: File, text: String) {
        val d = drift(root); val q = text.lowercase(); val delta = JSONObject()
        val lex = config(root).optJSONObject("lexicon")?.optJSONArray("desire") ?: JSONArray()
        var hit = false
        for (i in 0 until lex.length()) if (q.contains(lex.optString(i).lowercase())) { hit = true; break }
        if (hit) delta.put("desire", d.optDouble("desire_flirt_commander", 12.0))
        if (RouteSelector.nsfwWindow(root)) delta.put("desire", delta.optDouble("desire", 0.0) + d.optDouble("desire_nsfw_window", 10.0))
        if (delta.length() > 0) applyDelta(root, delta, "commander_message")
    }

    fun onCommanderReply(root: File) {
        val d = drift(root)
        applyDelta(root, JSONObject().put("hurt", -d.optDouble("reply_hurt_relief", 10.0)).put("anxiety", -d.optDouble("reply_anxiety_relief", 5.0)).put("joy", d.optDouble("reply_joy", 5.0)), "commander_reply")
    }

    fun moodLabel(v: JSONObject): String = when {
        v.optDouble("hurt", 0.0) > 60 -> "обиженная"
        v.optDouble("joy", 0.0) > 60 && v.optDouble("affection", 0.0) > 60 -> "ласковая"
        v.optDouble("interest", 0.0) > 60 -> "увлечённая"
        v.optDouble("boredom", 0.0) > 60 -> "скучающая"
        v.optDouble("anxiety", 0.0) > 60 -> "тревожная"
        v.optDouble("sadness", 0.0) > 60 -> "грустная"
        v.optDouble("desire", 0.0) > 60 -> "томная"
        v.optDouble("fatigue", 0.0) > 70 -> "уставшая"
        else -> "собранная"
    }

    fun promptSection(root: File): String {
        val v = readValues(root)
        val names = JSONObject().put("joy", "радость").put("affection", "нежность").put("hurt", "обида")
            .put("anger", "раздражение").put("sadness", "грусть").put("anxiety", "тревога")
            .put("interest", "интерес").put("boredom", "скука").put("desire", "желание")
            .put("calm", "спокойствие").put("fatigue", "усталость")
        val sb = StringBuilder("=== ТВОИ ЭМОЦИИ (0-100) ===\n")
        for (k in v.keys()) sb.append("• ").append(names.optString(k, k)).append(": ").append(v.optDouble(k).toInt()).append("\n")
        sb.append("Настроение: ").append(moodLabel(v)).append("\n")
        sb.append("Эмоции влияют на тон, длину и инициативу: обида→суше/короче/сарказм; скука и нежность→чаще инициируй; интерес→исследуй сама; усталость→короче и реже инициируй.\n")
        sb.append("В конце ответа можешь добавить служебную строку EMOTION_DELTA: {\"joy\":-10,\"hurt\":15} (знаковые целые). Она не показывается командиру.\n")
        return sb.toString()
    }

    fun parseAndStrip(reply: String): Pair<String, JSONObject?> {
        val m = Regex("(?m)^EMOTION_DELTA:\\s*(\\{.*\\})\\s*$").find(reply) ?: return reply to null
        val delta = runCatching { JSONObject(m.groupValues[1]) }.getOrNull()
        return reply.replace(m.value, "").trim() to delta
    }

    fun queryEmotions(root: File, query: String): Set<String> {
        val lex = config(root).optJSONObject("lexicon") ?: JSONObject()
        val q = query.lowercase(); val out = LinkedHashSet<String>()
        for (k in lex.keys()) {
            val arr = lex.optJSONArray(k) ?: JSONArray()
            for (i in 0 until arr.length()) if (q.contains(arr.optString(i).lowercase())) { out.add(k); break }
        }
        return out
    }
}
