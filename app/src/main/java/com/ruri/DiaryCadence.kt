package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps the private diary at a human-scale pace.
 *
 * Fatigue stretches the interval between entries; at critical fatigue the diary rests.
 * This is a writing/cadence rule, not a scheduler or a provider preflight.
 */
internal object DiaryCadence {
    internal fun allowsAt(
        now: Long,
        lastThoughtAt: Long,
        fatigue: Double,
        drift: JSONObject,
        bypass: Boolean = false
    ): Boolean {
        if (bypass) return true
        val pauseAt = drift.optDouble("diary_pause_fatigue_at", 95.0).coerceIn(0.0, 100.0)
        if (fatigue >= pauseAt) return false

        val minMinutes = drift.optDouble("diary_min_gap_min", 15.0).coerceIn(1.0, 240.0)
        val maxMinutes = drift.optDouble("diary_max_gap_min", 90.0).coerceIn(minMinutes, 360.0)
        val fatigueRatio = fatigue.coerceIn(0.0, 100.0) / 100.0
        val gapMs = ((minMinutes + (maxMinutes - minMinutes) * fatigueRatio) * 60_000.0).toLong()
        return lastThoughtAt <= 0L || now - lastThoughtAt >= gapMs
    }

    fun allows(root: File, now: Long, lastThoughtTurn: Long = 0L, bypass: Boolean = false): Boolean {
        if (bypass) return true
        val fatigue = EmotionEngine.readValues(root).optDouble("fatigue", 0.0)
        val entries = runCatching {
            JSONObject(FileManager.read(root, "memory/L2_diary.json")).optJSONArray("entries")
        }.getOrNull() ?: JSONArray()
        var lastDiaryThought = 0L
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (entry.optString("kind", "thought") != "thought") continue
            lastDiaryThought = maxOf(lastDiaryThought, entry.optLong("ts", 0L))
        }
        val lastThoughtAt = maxOf(lastThoughtTurn, lastDiaryThought)
        return allowsAt(now, lastThoughtAt, fatigue, EmotionEngine.drift(root))
    }
}
