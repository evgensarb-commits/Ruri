package com.ruri

import org.json.JSONObject
import java.io.File

/**
 * Detects an actual short-term opening loop from Ruri's own recent L0 messages.
 * The note is deliberately adaptive: it describes the observed pattern rather
 * than banning a phrase or prescribing one fixed alternative.
 */
object StyleGuard {
    private val leadingGesture = Regex("^\\s*\\*([^*]{1,60})\\*")
    private val stripLeadingGesture = Regex("^\\s*\\*[^*]{1,80}\\*\\s*")
    private val openingNoise = Regex("[^a-zа-яё0-9 ]")

    fun repetitiveOpeningNote(root: File): String {
        val hers = FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 200)
            .asReversed()
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { it.optString("type") == "message" && it.optString("who") == "ruri" }
            .take(4)
        val gestures = hers.mapNotNull { leadingGesture.find(it.optString("text"))?.groupValues?.get(1)?.trim() }
            .filter { it.isNotBlank() }
        if (gestures.size >= 3 && gestures.take(3).distinct().size == 1) {
            val safeGesture = SecretRedactor.redactText(gestures[0]).take(60)
            return "Твои последние ответы начинались с одного жеста («$safeGesture»). Варьируй вход: реплика/вопрос/мысль/ситуация, без повторяющейся ремарки-жеста."
        }
        val openings = hers.map {
            it.optString("text").lowercase()
                .replace(openingNoise, " ")
                .trim()
                .split(Regex("\\s+"))
                .take(6)
                .joinToString(" ")
        }.filter { it.isNotBlank() }
        if (openings.size >= 3 && openings.take(3).distinct().size == 1) {
            return "Твои последние ответы начинались одинаково («${openings[0]}»). Сломай паттерн, войди иначе."
        }
        return ""
    }

    fun stripOlderOpening(who: String, index: Int, total: Int, text: String): String {
        return if (who == "ruri" && index < total - 2) text.replace(stripLeadingGesture, "") else text
    }
}
