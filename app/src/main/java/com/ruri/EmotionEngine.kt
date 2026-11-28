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
            .put("idle_fatigue_relief_per_h", 2).put("idle_boredom_per_h", 4)
            .put("silence_longing_per_h", 5).put("silence_longing_after_h", 1.5)
            .put("fatigue_recovery_h", 7).put("silence_growth_h", 9).put("messages_to_satisfy", 8)
            .put("initiative_threshold", 30).put("initiative_gamma", 1.1).put("guarantee_at_100", true)
            .put("veto_allowed", true).put("initiative_mode", "drive_chance").put("drive_check_interval_min", 0)
            .put("initiative_cooldown_min", 10).put("activity_fatigue_message", 4).put("activity_fatigue_task", 6)
            .put("boredom_solo_relief", 4)
            .put("reply_longing_relief", 20).put("message_longing_relief", 10)
            .put("shelf_interest", 10).put("search_ok_interest", 8).put("search_ok_joy", 4)
            .put("search_fail_anger", 6).put("search_fail_anxiety", 3)
            .put("ignored_anger", 12).put("ignored_sadness", 10)
            .put("reply_anger_relief", 8).put("reply_sadness_relief", 6).put("reply_joy", 5)
            .put("desire_flirt_commander", 12).put("desire_evening_per_h", 1.5)
            .put("desire_from_affection_per_h", 1).put("desire_nsfw_window", 10)
            .put("base_initiative_h", 3.5).put("base_cooldown_h", 3)
            .put("thought_threshold", 18).put("thought_gamma", 0.9).put("thought_cooldown_min", 12))
        .put("lexicon", JSONObject()
            .put("sadness", JSONArray().put("груст").put("плохо").put("тоска").put("тяжело").put("печал"))
            .put("joy", JSONArray().put("рад").put("счаст").put("весел").put("круто").put("кайф"))
            .put("anger", JSONArray().put("зл").put("бесит").put("раздраж").put("достал"))
            .put("anxiety", JSONArray().put("тревож").put("боюсь").put("страш").put("беспоко"))
            .put("affection", JSONArray().put("люблю").put("нежн").put("обним"))
            .put("desire", JSONArray().put("хочу тебя").put("томн").put("возбужд").put("целую").put("малыш").put("пошл").put("секс").put("кис"))
            .put("longing", JSONArray().put("скучаю по тебе").put("тоскую").put("не хватает тебя").put("скучаю")))

    @Synchronized
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
        val now = System.currentTimeMillis()
        val lastTick = st.optLong("last_tick_at", 0L).takeIf { it > 0L } ?: now
        writeState(root, v, now, lastTick, st.optLong("last_cycle_at", 0L))
    }

    private fun config(root: File) = runCatching { JSONObject(FileManager.read(root, CONFIG)) }.getOrElse { defaults() }
    private fun channels(root: File): Map<String, JSONObject> {
        val o = config(root).optJSONObject("channels") ?: JSONObject(); val m = LinkedHashMap<String, JSONObject>()
        for (k in o.keys()) m[k] = o.optJSONObject(k) ?: JSONObject(); return m
    }
    fun drift(root: File): JSONObject = config(root).optJSONObject("drift") ?: JSONObject()

    private fun readState(root: File) = runCatching { JSONObject(FileManager.read(root, STATE)) }.getOrElse { JSONObject() }
    fun readValues(root: File): JSONObject {
        val v = readState(root).optJSONObject("values"); if (v != null) return v
        val out = JSONObject(); for ((n, c) in channels(root)) out.put(n, c.optDouble("base", 50.0)); return out
    }
    private fun writeState(root: File, v: JSONObject, now: Long, tick: Long, cycle: Long) {
        val state = readState(root)
        state.put("values", v).put("updated_at", now)
            .put("last_tick_at", tick).put("last_cycle_at", cycle)
        FileManager.write(root, STATE, state.toString(2))
    }
    private fun clamp(x: Double, c: JSONObject) = x.coerceIn(c.optDouble("min", 0.0), c.optDouble("max", 100.0))
    fun snapshot(root: File) = JSONObject(readValues(root).toString())

    @Synchronized
    fun applyDelta(root: File, delta: JSONObject, source: String): JSONObject =
        applyDeltaInternal(root, delta, source, null)

    @Synchronized
    private fun applyDeltaOnce(root: File, delta: JSONObject, source: String, messageId: String): JSONObject =
        applyDeltaInternal(root, delta, source, messageId.takeIf { it.isNotBlank() })

    private fun applyDeltaInternal(root: File, delta: JSONObject, source: String, messageId: String?): JSONObject {
        val now = System.currentTimeMillis()
        val state = readState(root)
        val processed = state.optJSONArray("processed_message_deltas") ?: JSONArray()
        val dedupeKey = messageId?.let { "$source:$it" }
        if (dedupeKey != null && (0 until processed.length()).any { processed.optString(it) == dedupeKey }) {
            val current = state.optJSONObject("values") ?: readValues(root)
            return JSONObject().put("before", current).put("delta", JSONObject())
                .put("after", current).put("mood", moodLabel(current)).put("duplicate", true)
        }
        val before = state.optJSONObject("values")?.let { JSONObject(it.toString()) } ?: readValues(root)
        val after = JSONObject()
        val applied = JSONObject()
        for ((name, channel) in channels(root)) {
            val current = before.optDouble(name, channel.optDouble("base", 50.0))
            val amount = delta.optDouble(name, 0.0)
            val next = if (amount.isFinite() && amount != 0.0) clamp(current + amount, channel) else current
            after.put(name, next)
            if (amount.isFinite() && amount != 0.0) applied.put(name, amount)
        }
        state.put("values", after).put("updated_at", now)
            .put("last_tick_at", state.optLong("last_tick_at", now))
            .put("last_cycle_at", state.optLong("last_cycle_at", 0L))
        if (dedupeKey != null) {
            processed.put(dedupeKey)
            while (processed.length() > 2000) processed.remove(0)
            state.put("processed_message_deltas", processed)
        }
        FileManager.write(root, STATE, state.toString(2))
        val mood = moodLabel(after)
        if (applied.length() > 0) FileManager.appendEvent(root, JSONObject()
            .put("type", "emotion_delta").put("source", source).put("delta", applied)
            .put("after", after).put("mood", mood).put("ts", now))
        return JSONObject().put("before", before).put("delta", applied).put("after", after).put("mood", mood)
    }

    @Synchronized
    fun decay(root: File, now: Long) {
        val st = readState(root); val dtH = (now - st.optLong("updated_at", now)) / HOUR; if (dtH <= 0) return
        val v = readValues(root); val out = JSONObject()
        for ((n, c) in channels(root)) {
            val b = c.optDouble("base", 50.0)
            if (n == "fatigue" || n == "boredom" || n == "longing") {
                out.put(n, v.optDouble(n, b))
                continue
            }
            val h = c.optDouble("half_life_h", 6.0).coerceAtLeast(0.1)
            out.put(n, clamp(b + (v.optDouble(n, b) - b) * exp(-LN2 * dtH / h), c)) }
        writeState(root, out, now, st.optLong("last_tick_at", now), st.optLong("last_cycle_at", 0L))
    }

    @Synchronized
    fun tick(root: File, now: Long, lastSeen: Long, lastEventAt: Long) {
        val st = readState(root)
        val lastTick = st.optLong("last_tick_at", 0L).takeIf { it > 0L } ?: now
        val dtH = ((now - lastTick) / HOUR).coerceAtLeast(0.0)
        // Silence pressure only accumulates after the latest real Commander message.
        // Keep fatigue and time-of-day desire on wall-clock time; conversation relieves
        // boredom/longing and must also stop their silence timer from running retroactively.
        val silenceStart = if (lastSeen > 0L) maxOf(lastTick, lastSeen) else lastTick
        val silenceDtH = ((now - silenceStart) / HOUR).coerceAtLeast(0.0)
        decay(root, now)
        val d = drift(root)
        val delta = JSONObject()
        if (dtH > 0) {
            val fatigue = readValues(root).optDouble("fatigue", 0.0)
            delta.put("fatigue", -minOf(fatigue, (100.0 / d.optDouble("fatigue_recovery_h", 7.0)) * dtH))
            if (silenceDtH > 0.0) {
                val growth = (100.0 / d.optDouble("silence_growth_h", 9.0)) * silenceDtH
                delta.put("boredom", growth)
                delta.put("longing", growth)
            }
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (hour in 20..23 || hour in 0..4) delta.put("desire", d.optDouble("desire_evening_per_h", 1.5) * dtH)
            if (readValues(root).optDouble("affection", 0.0) > 70.0)
                delta.put("desire", delta.optDouble("desire", 0.0) + d.optDouble("desire_from_affection_per_h", 1.0) * dtH)
        }
        if (delta.length() > 0) applyDelta(root, delta, "life")
        val s2 = readState(root)
        writeState(root, readValues(root), now, now, s2.optLong("last_cycle_at", 0L))
    }

    @Synchronized
    fun onCycle(root: File) {
        val st = readState(root)
        val now = System.currentTimeMillis()
        val lastTick = st.optLong("last_tick_at", 0L).takeIf { it > 0L } ?: now
        writeState(root, readValues(root), now, lastTick, now)
    }

    @Synchronized
    fun onActiveTask(root: File) {
        val d = drift(root)
        applyDelta(root, JSONObject()
            .put("fatigue", d.optDouble("activity_fatigue_task", 6.0))
            .put("boredom", -d.optDouble("boredom_solo_relief", 4.0)), "active_task")
    }

    /**
     * Deliberately NOT `@Synchronized`.
     *
     * LifeStateEngine holds the L4 file lock and calls in here, while this method reads L4 and
     * writes the emotion marker. Holding the emotion monitor across those L4 accesses acquired
     * the two locks in opposite orders in the two threads — a real deadlock: the reply never came
     * (no error at all) and the worker was killed as an ANR. The L4 touches are now outside any
     * emotion monitor, and duplicate application is still prevented by the dedupe marker in
     * [applyDeltaOnce].
     */
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
        if (RouteSelector.isAdultTopic(text)) {
            delta.put("desire", delta.optDouble("desire", 0.0) + d.optDouble("desire_nsfw_window", 10.0))
        }
        val satisfaction = 100.0 / d.optDouble("messages_to_satisfy", 8.0)
        delta.put("fatigue", delta.optDouble("fatigue", 0.0) + d.optDouble("activity_fatigue_message", 4.0))
        delta.put("boredom", delta.optDouble("boredom", 0.0) - satisfaction)
        delta.put("longing", delta.optDouble("longing", 0.0) - satisfaction)
        if (msgId.isNotBlank()) applyDeltaOnce(root, delta, "commander_message", msgId)
        else applyDelta(root, delta, "commander_message")
        if (msgId.isNotBlank()) {
            FileManager.updateL4EmotionMarker(root, "last_emotion_msg_id", msgId)
        }
    }

    /** Not `@Synchronized`: see [onCommanderMessage] — same L4 ordering rule. */
    fun onCommanderReply(root: File, msgId: String = "") {
        val l4 = runCatching {
            JSONObject(FileManager.read(root, "memory/L4_state.json"))
        }.getOrElse { JSONObject() }
        if (msgId.isNotBlank() && l4.optString("last_reply_emotion_msg_id") == msgId) return

        val d = drift(root)
        val delta = JSONObject().put("anger", -d.optDouble("reply_anger_relief", 8.0))
            .put("sadness", -d.optDouble("reply_sadness_relief", 6.0)).put("joy", d.optDouble("reply_joy", 5.0))
            .put("longing", -d.optDouble("reply_longing_relief", 20.0))
        if (msgId.isNotBlank()) applyDeltaOnce(root, delta, "commander_reply", msgId)
        else applyDelta(root, delta, "commander_reply")
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

    fun pressure(root: File): Double {
        val v = readValues(root)
        return (v.optDouble("boredom", 0.0) + v.optDouble("longing", 0.0)) / 2.0
    }

    fun initiativeGate(root: File, lastSeen: Long, now: Long, waiting: Boolean, lastInitiative: Long): Boolean {
        if (waiting) return false
        val d = drift(root)
        // A high leftover pressure must not trigger an unsolicited message immediately after
        // a conversation. Require a real quiet window independently of the emotional roll.
        val minimumSilenceMs = d.optLong("initiative_min_silence_min", 15L).coerceAtLeast(0L) * 60_000L
        if (lastSeen > 0L && (now - lastSeen).coerceAtLeast(0L) < minimumSilenceMs) return false
        val p = pressure(root)
        val threshold = d.optDouble("initiative_threshold", 30.0)
        if (p < threshold) return false
        val cooldown = initiativeCooldownMs(d, p, threshold)
        if (lastInitiative > 0L && now - lastInitiative < cooldown) return false
        val mode = d.optString("initiative_mode", "drive_chance")
        if (mode == "strict_guarantee") return p >= 100.0
        if (mode == "pure_autonomy") return true
        if (p >= 100.0 && d.optBoolean("guarantee_at_100", true)) return true
        val chance = Math.pow(((p - threshold) / (100.0 - threshold)).coerceIn(0.0, 1.0),
            d.optDouble("initiative_gamma", 1.1))
        val roll = initiativeRoll(now, p, lastSeen, lastInitiative)
        val passed = roll < chance
        FileManager.appendEvent(root, JSONObject()
            .put("type", "initiative_gate_evaluated")
            .put("pressure", p)
            .put("threshold", threshold)
            .put("cooldown_sec", cooldown / 1000L)
            .put("chance", chance)
            .put("roll", roll)
            .put("passed", passed)
            .put("ts", now))
        return passed
    }

    /**
     * The pause after her last initiative is emotional, not a scheduler: the more boredom
     * and longing she feels, the shorter it becomes. At full pressure it nearly disappears.
     */
    internal fun initiativeCooldownMs(drift: JSONObject, pressure: Double, threshold: Double): Double {
        val baseMinutes = drift.optDouble("initiative_cooldown_min", 10.0).coerceAtLeast(1.0)
        val span = (100.0 - threshold).coerceAtLeast(1.0)
        val heat = ((pressure - threshold) / span).coerceIn(0.0, 1.0)
        val factor = 1.0 - heat * 0.85
        return baseMinutes * factor * 60_000.0
    }

    /**
     * A thought turn has its own, softer gate than an initiative.
     *
     * Writing an entry to her diary needs less pressure than addressing the Commander, so
     * she keeps an inner life while she is not yet ready to message him. Both gates are
     * driven purely by emotion: no interval decides anything.
     */
    /**
     * Whether a thought turn may happen.
     *
     * The diary is her thinking log — the trace of a thought process, like a model's reasoning
     * trace. It used to be gated on emotional pressure (threshold 18 of 100) plus a long
     * cooldown, so a calm stretch produced no entries and the diary stayed empty for weeks.
     *
     * Pressure now shapes *what* she writes, never *whether* she writes. Only a short anti-spam
     * window remains: two entries minutes apart would be the same thought counted twice.
     */
    fun thoughtGate(root: File, now: Long, lastThoughtTurn: Long): Boolean {
        val d = drift(root)
        val p = pressure(root)
        val threshold = d.optDouble("thought_threshold", 18.0)
        val minGap = d.optLong("thought_min_gap_sec", 90L) * 1000L
        if (lastThoughtTurn > 0L && now - lastThoughtTurn < minGap) return false
        val span = (100.0 - threshold).coerceAtLeast(1.0)
        // Telemetry only: how strongly she was moved, not whether she may write.
        val intensity = Math.pow(((p - threshold) / span).coerceIn(0.0, 1.0),
            d.optDouble("thought_gamma", 0.9))
        val roll = initiativeRoll(now, p, lastThoughtTurn, 0L)
        FileManager.appendEvent(root, JSONObject()
            .put("type", "thought_gate_evaluated")
            .put("pressure", p)
            .put("threshold", threshold)
            .put("intensity", intensity)
            .put("roll", roll)
            .put("passed", true)
            .put("ts", now))
        return true
    }

    internal fun thoughtCooldownMs(drift: JSONObject, pressure: Double, threshold: Double): Double {
        val baseMinutes = drift.optDouble("thought_cooldown_min", 12.0).coerceAtLeast(1.0)
        val span = (100.0 - threshold).coerceAtLeast(1.0)
        val heat = ((pressure - threshold) / span).coerceIn(0.0, 1.0)
        return baseMinutes * (1.0 - heat * 0.9) * 60_000.0
    }

    internal fun initiativeRoll(now: Long, pressure: Double, lastSeen: Long, lastInitiative: Long): Double {
        var value = now xor pressure.toBits() xor
            (lastSeen * -7046029254386353131L) xor
            (lastInitiative * -4658895280553007687L)
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        value = value xor (value ushr 31)
        return (value ushr 11).toDouble() / 9007199254740992.0
    }

    fun promptSection(root: File): String {
        val v = readValues(root)
        val names = JSONObject().put("joy", "радость").put("affection", "нежность").put("anger", "злость")
            .put("sadness", "грусть").put("anxiety", "страх").put("interest", "интерес").put("boredom", "скука")
            .put("desire", "желание").put("fatigue", "усталость").put("longing", "тоска по командиру")
        val sb = StringBuilder("=== ТВОИ ЭМОЦИИ (0-100) ===\n")
        for (k in v.keys()) sb.append("• ").append(names.optString(k, k)).append(": ").append(v.optDouble(k).toInt()).append("\n")
        sb.append("Настроение: ").append(moodLabel(v)).append("\n")
        sb.append("Шкала: 0=нет, 50=умеренно, 100=предел. Злость:0 спокойна/50 раздражена/100 в ярости. Грусть:0 лёгкая/50 печаль/100 тоска. Страх:0 уверена/50 тревожно/100 паника. Скука:0 занята/50 скучно/100 невыносимо. Тоска по командиру:0 не скучаю/50 скучаю/100 места не нахожу. Усталость:0 свежа/50 устала/100 вымотана. Желание:30 фон/60 томно/100 горит.\n")
        val d = drift(root)
        sb.append("Давление скуки+тоски: ").append(pressure(root).toInt()).append("/100. Порог инициативы: ").append(d.optDouble("initiative_threshold", 40.0).toInt()).append(".\n")
        sb.append("Когда решаешь писать командиру или подумать, можешь добавить строку INITIATIVE_DECISION: write|think|veto: причина. veto = отложить по своей причине (автономия).\n")
        sb.append("Эмоции влияют на тон и выбор слов, а НЕ требуют обязательной ремарки-жеста в начале сообщения. Злость+грусть→суше/короче; скука и тоска и нежность→чаще инициируй; интерес→исследуй; усталость→короче/реже.\n")
        sb.append("В конце ответа отдельной строкой можешь добавить EMOTION_DELTA: {\"joy\":-10,\"anger\":15} (знаковые целые, каналы выше). Строка не показывается командиру.\n")
        return sb.toString()
    }

    fun parseInitiativeDecision(reply: String): Pair<String, String> {
        val match = Regex("(?m)^INITIATIVE_DECISION:\\s*(write|think|veto)\\s*:?\\s*(.*)$").find(reply)
            ?: return reply to ""
        val detail = match.groupValues[2].trim()
        return reply.replace(match.value, "").trim() to
            (match.groupValues[1] + if (detail.isNotBlank()) ": $detail" else "")
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
