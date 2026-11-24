package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Small, verifiable outcome summary built only from the local append-only event log.
 * It deliberately reports observations, not model interpretations.
 */
object OutcomeDigest {
    private const val L0_PATH = "memory/L0_raw.jsonl"
    private const val STATE_PATH = "memory/L4_state.json"
    private const val MAX_FAILURES = 5

    private val failureTypes = setOf(
        "model_error", "nsfw_refusal", "thinker_error"
    )

    private fun readEvents(root: File): List<JSONObject> =
        FileManager.readLastLines(root, L0_PATH, maxLines = 800, maxBytes = 2 * 1024 * 1024)
            .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }

    private fun data(event: JSONObject): JSONObject = event.optJSONObject("data") ?: JSONObject()

    private fun value(event: JSONObject, key: String): Any? =
        if (event.has(key)) event.opt(key) else data(event).opt(key)

    private fun string(event: JSONObject, key: String): String =
        value(event, key)?.let { if (it == JSONObject.NULL) "" else it.toString() }.orEmpty()

    private fun timestamp(event: JSONObject): Long =
        event.optLong("ts", event.optLong("timestamp", 0L))

    private fun isFailure(event: JSONObject): Boolean {
        val type = event.optString("type")
        if (type == "tool_result") return string(event, "ok").equals("false", true)
        return type in failureTypes
    }

    private fun failureLine(event: JSONObject): String {
        val type = event.optString("type", "failure")
        val action = string(event, "action").takeIf { it.isNotBlank() }
        val route = string(event, "route").takeIf { it.isNotBlank() }
        val details = sequenceOf("text", "snippet", "message", "reason", "error")
            .map { string(event, it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return buildString {
            append("- ").append(type)
            if (action != null) append(" / ").append(action)
            if (route != null) append(" / ").append(route)
            if (details.isNotBlank()) append(": ").append(SecretRedactor.redactText(details).take(220))
        }
    }

    /** Returns a compact diagnostic section for the next system prompt or self_check. */
    fun build(root: File): String {
        val events = readEvents(root)
        val failures = events.asReversed().filter(::isFailure).take(MAX_FAILURES)
        val replyIndex = events.indexOfLast {
            it.optString("type") == "message" && it.optString("who") == "ruri"
        }
        val previousReply = events.getOrNull(replyIndex)
        val commanderIndex = if (replyIndex >= 0) {
            events.subList(0, replyIndex).indexOfLast {
                it.optString("type") == "message" && it.optString("who") == "commander"
            }
        } else -1
        val wakeIndex = events.subList(0, (replyIndex.coerceAtLeast(0))).indexOfLast {
            it.optString("type") == "wake"
        }
        val turnStart = if (wakeIndex > commanderIndex) wakeIndex + 1 else commanderIndex + 1
        val turnEvents = if (replyIndex > turnStart) events.subList(turnStart, replyIndex) else emptyList()
        val toolResults = turnEvents.filter { it.optString("type") == "tool_result" }
        val successfulTools = toolResults.count { string(it, "ok").equals("true", true) }
        val failedTools = toolResults.size - successfulTools
        val correction = runCatching {
            JSONObject(FileManager.read(root, STATE_PATH)).optJSONObject("pending_correction")
        }.getOrNull()

        return buildString {
            append("=== ИТОГИ И ПРОВЕРЯЕМОСТЬ ПРЕДЫДУЩЕГО ХОДА ===\n")
            if (previousReply == null) {
                append("Предыдущий ответ: ещё не записан.\n")
            } else {
                append("Последний ответ: id=").append(previousReply.optString("id", "—"))
                    .append(", ts=").append(timestamp(previousReply))
                    .append("; инструменты ok=").append(successfulTools)
                    .append(", failed=").append(failedTools)
                    .append(".\n")
            }
            append("Последние ошибки L0 (не более пяти):\n")
            if (failures.isEmpty()) append("- не найдено\n")
            else failures.forEach { append(failureLine(it)).append('\n') }
            if (correction != null) {
                append("ОБЯЗАТЕЛЬНОЕ ИСПРАВЛЕНИЕ ПРЕДЫДУЩЕГО УТВЕРЖДЕНИЯ: ")
                    .append(SecretRedactor.redactText(correction.optString("message")).take(500))
                    .append('\n')
            }
            append("Не заявляй о том, чего у тебя нет: ни поиска в интернете, ни чтения документов, ни просмотра изображений.")
        }
    }

    /** Call after adding build(root) to a prompt: the correction is then shown exactly once. */
    fun clearPresentedCorrection(root: File) {
        runCatching {
            FileManager.updateL4State(root) { state -> state.remove("pending_correction") }
        }
    }

    /**
     * Compares capability claims with what the app can actually do. The single-route
     * build has no external tools at all, so any claim of searching, reading documents
     * or looking at images is a hallucination that must be corrected in the next prompt.
     */
    fun verifyClaims(root: File, reply: String): JSONObject? {
        val lower = reply.lowercase()
        val claimsSearch = Regex("(поискал|поискала|поиcкал|поиcкала|искал в интернете|искала в интернете|наш[её]л в интернете|нашла в интернете|по результатам поиска)")
            .containsMatchIn(lower)
        val claimsRead = Regex("(прочитал|прочитала|изучил|изучила|ознакомился|ознакомилась) (файл|документ|страниц|стать|содержим)")
            .containsMatchIn(lower)
        val claimsVision = Regex("(на фото|на изображении|на картинке|вижу на снимке|увидел на снимке|увидела на снимке)")
            .containsMatchIn(lower)
        if (!claimsSearch && !claimsRead && !claimsVision) return null

        val mismatches = mutableListOf<String>()
        if (claimsSearch) {
            mismatches += "Ответ утверждал, что поиск выполнен, но внешнего поиска у меня нет: в этой сборке нет ни одного инструмента."
        }
        if (claimsRead) {
            mismatches += "Ответ утверждал, что документ прочитан, но доступа к документам у меня нет: в этой сборке нет ни одного инструмента."
        }
        if (claimsVision) {
            mismatches += "Ответ утверждал, что изображение просмотрено, но я не получаю изображений: в этой сборке нет ни одного инструмента."
        }
        if (mismatches.isEmpty()) return null

        val correction = JSONObject()
            .put("message", mismatches.joinToString(" "))
            .put("claims", JSONArray().apply {
                if (claimsSearch) put("search")
                if (claimsRead) put("read")
                if (claimsVision) put("vision")
            })
            .put("recorded_at", System.currentTimeMillis())
        FileManager.updateL4State(root) { state -> state.put("pending_correction", correction) }
        FileManager.appendEvent(root, JSONObject()
            .put("type", "claim_mismatch")
            .put("snippet", SecretRedactor.redactText(reply).take(240))
            .put("mismatch_count", mismatches.size)
            .put("ts", System.currentTimeMillis()))
        return correction
    }
}
