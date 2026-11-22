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

    private fun ch(min: Int, max: Int, base: Int, half: Int) = JSONObject()
        .put("min", min).put("max", max).put("base", base).put("half_life_h", half)

    private fun defaults() = JSONObject().put("schema_version", 2)
        .put("channels", JSONObject()
            .put("joy", ch(0, 100, 50, 6)).put("affection", ch(0, 100, 50, 12))
            .put("anger", ch(0, 100, 10, 3)).put("sadness", ch(0, 100, 20, 8))
            .put("anxiety", ch(0, 100, 20, 4)).put("interest", ch(0, 100, 30, 2))
            .put("boredom", ch(0, 100, 20, 1)).put("desire", ch(30, 100, 30, 6))
            .put("fatigue", ch(0, 100, 20, 4)).put("longing", ch(0, 100, 20, 6)))
        .put("drift", JSONObject()
            .put("activity_fatigue", 3).put("activity_boredom_relief", 6)
            .put("cycle_fatigue", 2).put("cycle_boredom_relief", 4)
            .put("idle_fatigue_relief_per_h", 2).put("idle_boredom_per_h", 2)
            .put("silence_longing_per_h", 3).put("silence_longing_after_h", 2)
            .put("reply_longing_relief", 20).put("message_longing_relief", 10)
            .put("shelf_interest", 10).put("search_ok_interest", 8).put("search_ok_joy", 4)
            .put("search_fail_anger", 6).put("search_fail_anxiety", 3)
            .put("ignored_anger", 12).put("ignored_sadness", 10)
            .put("reply_anger_relief", 8).put("reply_sadness_relief", 6).put("reply_joy", 5)
            .put("desire_flirt_commander", 12).put("desire_evening_per_h", 1.5)
            .put("desire_from_affection_per_h", 1).put("desire_nsfw_window", 10)
            .put("base_initiative_h", 3.5).put("base_cooldown_h", 3))
        .put("lexicon", JSONObject()
            .put("sadness", JSONArray().put("груст").put("плохо").put("тоска").put("тяжело").put("печал"))
            .put("joy", JSONArray().put("рад").put("счаст").put("весел").put("круто").put("кайф"))
            .put("anger", JSONArray().put("зл").put("бесит").put("раздраж").put("достал"))
            .put("anxiety", JSONArray().put("тревож").put("боюсь").put("страш").put("беспоко"))
            .put("affection", JSONArray().put("люблю").put("нежн").put("обним"))
            .put("desire", JSONArray().put("хочу тебя").put("томн").put("возбужд").put("целую").put("малыш").put("пошл").put("секс").put("кис"))
            .put("longing", JSONArray().put("скучаю по тебе").put("тоскую").put("не хватает тебя").put("скучаю")))

    fun ensure(root: File) {
        if (!FileManager.exists(root, CONFIG)) FileManager.write(root, CONFIG, defaults().toString(2))
        val cfg = config(root); val def = defaults()
        val dc = def.optJSONObject("channels"); val cc = cfg.optJSONObject("channels") ?: JSONObject().also { cfg.put("channels", it) }
        // Remove retired channels before merging defaults; otherwise legacy configs would keep
        // recreating hurt/calm in every state snapshot after the value migration below.
        cc.remove("hurt")
        cc.remove("calm")
        for (k in dc.keys()) if (!cc.has(k)) cc.put(k, dc.get(k))
        val st = readState(root); val v = st.optJSONObject("values") ?: JSONObject()
        if (v.has("hurt")) { val h = v.optDouble("hurt", 0.0)
            v.put("anger", v.optDouble("anger", 0.0) + h / 2); v.put("sadness", v.optDouble("sadness", 0.0) + h / 2); v.remove("hurt") }
        v.remove("calm")
        for (k in cc.keys()) if (!v.has(k)) v.put(k, cc.optJSONObject(k).optDouble("base", 50.0))
        if (!cfg.optBoolean("fatigue_reset_v1", false)) {
            v.put("fatigue", 50.0)
            cfg.put("fatigue_reset_v1", true)
        }
        val dd = def.optJSONObject("drift"); val cd = cfg.optJSONObject("drift") ?: JSONObject().also { cfg.put("drift", it) }
        for (k in dd.keys()) if (!cd.has(k)) cd.put(k, dd.get(k))
        val dl = def.optJSONObject("lexicon"); val cl = cfg.optJSONObject("lexicon") ?: JSONObject().also { cfg.put("lexicon", it) }
        for (k in dl.keys()) { val arr = cl.optJSONArray(k) ?: JSONArray().also { cl.put(k, it) }; val da = dl.optJSONArray(k)
            for (i in 0 until da.length()) { val w = da.optString(i); var found = false
                for (j in 0 until arr.length()) if (arr.optString(j) == w) found = true
                if (!found) arr.put(w) } }
        cfg.put("schema_version", 2)
        FileManager.write(root, CONFIG, cfg.toString(2))
        writeState(root, v, System.currentTimeMillis(), st.optLong("last_tick_at", 0L), st.optLong("last_cycle_at", 0L))
    }

    private fun config(root: File) = runCatching { JSONObject(FileManager.read(root, CONFIG)) }.getOrElse { defaults() }
    private fun channels(root: File): Map<String, JSONObject> {
        val o = config(root).optJSONObject("channels") ?: JSONObject(); val m = LinkedHashMap<String, JSONObject>()
        for (k in o.keys()) m[k] = o.optJSONObject(k) ?: JSONObject(); return m
    }
    private fun drift(root: File) = config(root).optJSONObject("drift") ?: JSONObject()

    // Compatibility for LifeScheduler: the new initiative gate is handled directly by
    // initiativeGate(), while this snapshot still exposes the adaptive reflection interval.
    fun thresholds(root: File): JSONObject {
        val v = readValues(root)
        val d = drift(root)
        val channelConfig = config(root).optJSONObject("channels") ?: JSONObject()
        fun n(key: String): Double {
            val base = channelConfig.optJSONObject(key)?.optDouble("base", 0.0) ?: 0.0
            return (v.optDouble(key, base) - base) / 100.0
        }
        val initiative = (d.optDouble("base_initiative_h", 3.5)
            + (n("anger") + n("sadness")) * 2.0 + n("fatigue") * 2.0
            - n("longing") * 1.5 - n("boredom") - n("affection")).coerceIn(0.5, 8.0)
        val cooldown = (d.optDouble("base_cooldown_h", 3.0)
            + n("fatigue") * 2.0 - n("boredom")).coerceIn(0.5, 6.0)
        val reflection = (6.0 - n("interest") * 2.0 - n("boredom") * 2.0
            + n("fatigue") * 2.0).coerceIn(1.0, 12.0)
        return JSONObject()
            .put("initiative_h", initiative)
            .put("cooldown_h", cooldown)
            .put("reflection_h", reflection)
    }
    private fun readState(root: File) = runCatching { JSONObject(FileManager.read(root, STATE)) }.getOrElse { JSONObject() }
    fun readValues(root: File): JSONObject {
        val v = readState(root).optJSONObject("values"); if (v != null) return v
        val out = JSONObject(); for ((n, c) in channels(root)) out.put(n, c.optDouble("base", 50.0)); return out
    }
    private fun writeState(root: File, v: JSONObject, now: Long, tick: Long, cycle: Long) {
        FileManager.write(root, STATE, JSONObject().put("values", v).put("updated_at", now)
            .put("last_tick_at", tick).put("last_cycle_at", cycle).toString(2))
    }
    private fun clamp(x: Double, c: JSONObject) = x.coerceIn(c.optDouble("min", 0.0), c.optDouble("max", 100.0))
    fun snapshot(root: File) = JSONObject(readValues(root).toString())

    fun applyDelta(root: File, delta: JSONObject, source: String): JSONObject {
        val now = System.currentTimeMillis(); val before = readValues(root)
        val after = JSONObject(); val applied = JSONObject()
        for ((n, c) in channels(root)) {
            val cur = before.optDouble(n, c.optDouble("base", 50.0)); val d = delta.optDouble(n, 0.0)
            val nv = if (d.isFinite() && d != 0.0) clamp(cur + d, c) else cur
            after.put(n, nv); if (d.isFinite() && d != 0.0) applied.put(n, d)
        }
        val st = readState(root)
        writeState(root, after, now, st.optLong("last_tick_at", now), st.optLong("last_cycle_at", 0L))
        val mood = moodLabel(after)
        if (applied.length() > 0) FileManager.appendEvent(root, JSONObject()
            .put("type", "emotion_delta").put("source", source).put("delta", applied)
            .put("after", after).put("mood", mood).put("ts", now))
        return JSONObject().put("before", before).put("delta", applied).put("after", after).put("mood", mood)
    }

    fun decay(root: File, now: Long) {
        val st = readState(root); val dtH = (now - st.optLong("updated_at", now)) / HOUR; if (dtH <= 0) return
        val v = readValues(root); val out = JSONObject()
        for ((n, c) in channels(root)) { val b = c.optDouble("base", 50.0); val h = c.optDouble("half_life_h", 6.0).coerceAtLeast(0.1)
            out.put(n, clamp(b + (v.optDouble(n, b) - b) * exp(-LN2 * dtH / h), c)) }
        writeState(root, out, now, st.optLong("last_tick_at", now), st.optLong("last_cycle_at", 0L))
    }

    fun tick(root: File, now: Long, lastSeen: Long, lastEventAt: Long) {
        val st = readState(root); val dtH = ((now - st.optLong("last_tick_at", now)) / HOUR).coerceAtLeast(0.0)
        decay(root, now); val d = drift(root); val delta = JSONObject()
        if (dtH > 0) {
            delta.put("fatigue", -d.optDouble("idle_fatigue_relief_per_h", 2.0) * dtH)
            delta.put("boredom", d.optDouble("idle_boredom_per_h", 2.0) * dtH)
            val sil = if (lastSeen > 0) (now - lastSeen) / HOUR else 0.0
            if (sil > d.optDouble("silence_longing_after_h", 2.0))
                delta.put("longing", d.optDouble("silence_longing_per_h", 3.0) * dtH)
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (hour in 20..23 || hour in 0..4) delta.put("desire", d.optDouble("desire_evening_per_h", 1.5) * dtH)
            if (readValues(root).optDouble("affection", 0.0) > 70) delta.put("desire", delta.optDouble("desire", 0.0) + d.optDouble("desire_from_affection_per_h", 1.0) * dtH)
        }
        if (delta.length() > 0) applyDelta(root, delta, "life")
        val s2 = readState(root); writeState(root, readValues(root), now, now, s2.optLong("last_cycle_at", 0L))
    }

    fun onCycle(root: File) { val d = drift(root)
        applyDelta(root, JSONObject().put("fatigue", d.optDouble("cycle_fatigue", 2.0)).put("boredom", -d.optDouble("cycle_boredom_relief", 4.0)), "cycle")
        val st = readState(root); val now = System.currentTimeMillis()
        writeState(root, readValues(root), now, st.optLong("last_tick_at", now), now) }

    @Synchronized
    @Synchronized
    fun onCommanderMessage(root: File, text: String, msgId: String = "") {
        val l4 = runCatching {
            JSONObject(FileManager.read(root, "memory/L4_state.json"))
        }.getOrElse { JSONObject() }
        if (msgId.isNotBlank() && l4.optString("last_emotion_msg_id") == msgId) return

        val d = drift(root); val q = text.lowercase(); val delta = JSONObject()
        val lex = config(root).optJSONObject("lexicon")?.optJSONArray("desire") ?: JSONArray()
        for (i in 0 until lex.length()) {
            if (q.contains(lex.optString(i).lowercase())) {
                delta.put("desire", d.optDouble("desire_flirt_commander", 12.0))
                break
            }
        }
        if (RouteSelector.nsfwWindow(root)) {
            delta.put("desire", delta.optDouble("desire", 0.0) + d.optDouble("desire_nsfw_window", 10.0))
        }
        delta.put("fatigue", delta.optDouble("fatigue", 0.0) + d.optDouble("activity_fatigue", 3.0))
        delta.put("boredom", delta.optDouble("boredom", 0.0) - d.optDouble("activity_boredom_relief", 6.0))
        delta.put("longing", -d.optDouble("message_longing_relief", 10.0))
        applyDelta(root, delta, "commander_message")
        if (msgId.isNotBlank()) {
            FileManager.updateL4EmotionMarker(root, "last_emotion_msg_id", msgId)
        }
    }

    @Synchronized
    fun onCommanderReply(root: File, msgId: String = "") {
        val l4 = runCatching {
            JSONObject(FileManager.read(root, "memory/L4_state.json"))
        }.getOrElse { JSONObject() }
        if (msgId.isNotBlank() && l4.optString("last_reply_emotion_msg_id") == msgId) return

        val d = drift(root)
        applyDelta(root, JSONObject().put("anger", -d.optDouble("reply_anger_relief", 8.0))
            .put("sadness", -d.optDouble("reply_sadness_relief", 6.0)).put("joy", d.optDouble("reply_joy", 5.0))
            .put("longing", -d.optDouble("reply_longing_relief", 20.0)), "commander_reply")
        if (msgId.isNotBlank()) {
            FileManager.updateL4EmotionMarker(root, "last_reply_emotion_msg_id", msgId)
        }
    }

    fun onIgnored(root: File) { val d = drift(root)
        applyDelta(root, JSONObject().put("anger", d.optDouble("ignored_anger", 12.0)).put("sadness", d.optDouble("ignored_sadness", 10.0)), "ignored") }

    fun onShelfFile(root: File) = applyDelta(root, JSONObject().put("interest", drift(root).optDouble("shelf_interest", 10.0)), "shelf")

    fun onSearchResult(root: File, ok: Boolean) { val d = drift(root)
        applyDelta(root, if (ok) JSONObject().put("interest", d.optDouble("search_ok_interest", 8.0)).put("joy", d.optDouble("search_ok_joy", 4.0))
        else JSONObject().put("anger", d.optDouble("search_fail_anger", 6.0)).put("anxiety", d.optDouble("search_fail_anxiety", 3.0)), "search") }

    fun moodLabel(v: JSONObject): String = when {
        v.optDouble("anger", 0.0) > 60 && v.optDouble("sadness", 0.0) > 50 -> "обиженная"
        v.optDouble("anger", 0.0) > 60 -> "раздражённая"
        v.optDouble("sadness", 0.0) > 60 -> "грустная"
        v.optDouble("longing", 0.0) > 60 -> "тоскующая"
        v.optDouble("anxiety", 0.0) > 60 -> "тревожная"
        v.optDouble("joy", 0.0) > 60 && v.optDouble("affection", 0.0) > 60 -> "ласковая"
        v.optDouble("interest", 0.0) > 60 -> "увлечённая"
        v.optDouble("boredom", 0.0) > 60 -> "скучающая"
        v.optDouble("desire", 0.0) > 60 -> "томная"
        v.optDouble("fatigue", 0.0) > 70 -> "уставшая"
        else -> "собранная"
    }

    fun initiativeGate(root: File, lastSeen: Long, now: Long, waiting: Boolean, lastInitiative: Long): Boolean {
        if (lastSeen <= 0 || waiting) return false
        val v = readValues(root); val d = drift(root)
        fun n(k: String) = v.optDouble(k, 0.0) / 100.0
        val thr = (d.optDouble("base_initiative_h", 3.5) + (n("anger") + n("sadness")) * 2 + n("fatigue") * 2
            - n("longing") * 1.5 - n("boredom") * 1 - n("affection") * 1).coerceIn(0.5, 8.0)
        val cool = (d.optDouble("base_cooldown_h", 3.0) + n("fatigue") * 2 - n("boredom") * 1).coerceIn(0.5, 6.0)
        val sil = (now - lastSeen) / HOUR
        return sil >= thr && (lastInitiative == 0L || now - lastInitiative >= (cool * HOUR).toLong())
    }

    fun promptSection(root: File): String {
        val v = readValues(root)
        val names = JSONObject().put("joy", "радость").put("affection", "нежность").put("anger", "злость")
            .put("sadness", "грусть").put("anxiety", "страх").put("interest", "интерес").put("boredom", "скука")
            .put("desire", "желание").put("fatigue", "усталость").put("longing", "тоска по командиру")
        val sb = StringBuilder("=== ТВОИ ЭМОЦИИ (0-100) ===\n")
        for (k in v.keys()) sb.append("• ").append(names.optString(k, k)).append(": ").append(v.optDouble(k).toInt()).append("\n")
        sb.append("Настроение: ").append(moodLabel(v)).append("\n")
        sb.append("Эмоции влияют на тон/длину/инициативу: злость+грусть→суше/короче; скука и тоска и нежность→чаще инициируй; интерес→исследуй; усталость→короче/реже.\n")
        sb.append("В конце ответа отдельной строкой можешь добавить EMOTION_DELTA: {\"joy\":-10,\"anger\":15} (знаковые целые, каналы выше). Строка не показывается командиру.\n")
        return sb.toString()
    }

    fun parseAndStrip(reply: String): Pair<String, JSONObject?> {
        val m = Regex("(?m)^EMOTION_DELTA:\\s*(\\{.*\\})\\s*$").find(reply) ?: return reply to null
        val delta = runCatching { JSONObject(m.groupValues[1]) }.getOrNull()
        return reply.replace(m.value, "").trim() to delta
    }

    fun queryEmotions(root: File, query: String): Set<String> {
        val lex = config(root).optJSONObject("lexicon") ?: JSONObject(); val q = query.lowercase(); val out = LinkedHashSet<String>()
        for (k in lex.keys()) { val a = lex.optJSONArray(k) ?: JSONArray()
            for (i in 0 until a.length()) if (q.contains(a.optString(i).lowercase())) { out.add(k); break } }
        return out
    }
}
