package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Shared action protocol and prose guard for interactive and autonomous model turns. */
object ToolLoop {
    private val allowed = setOf(
        "recall", "remember_fact", "self_check", "read_url", "read_document", "look_at_image",
        "read_raw", "challenge_claim"
    )

    private fun extractJsonObjects(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '{') { i++; continue }
            var depth = 0
            var j = i
            var inStr = false
            var esc = false
            while (j < text.length) {
                val c = text[j]
                if (inStr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') inStr = false
                } else when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            out.add(text.substring(i, j + 1))
                            break
                        }
                    }
                }
                j++
            }
            i = j + 1
        }
        return out
    }

    fun stripActions(reply: String): Triple<String, List<String>, List<JSONObject>> {
        val direct = parseJsonAction(reply)
        if (direct != null) {
            val text = direct.optString("text").trim()
            val diary = if (direct.optString("action") in setOf("write_diary", "diary") && text.isNotBlank())
                listOf(SecretRedactor.redactText(text)) else emptyList()
            val tools = if (direct.optString("action") in allowed) listOf(direct) else emptyList()
            return Triple("", diary, tools)
        }
        val diary = mutableListOf<String>(); val tools = mutableListOf<JSONObject>()
        var cleaned = reply
        for (objectText in extractJsonObjects(reply)) {
            val action = runCatching { JSONObject(objectText) }.getOrNull()
                ?.takeIf { it.has("action") } ?: continue
            cleaned = cleaned.replace(objectText, "")
            when (action.optString("action")) {
                "write_diary", "diary" -> action.optString("text").trim().takeIf { it.isNotBlank() }?.let(diary::add)
                "recall", "remember_fact", "self_check", "read_url", "read_document",
                "look_at_image", "read_raw", "challenge_claim" -> tools.add(action)
                else -> Unit
            }
        }
        return Triple(cleaned.replace(Regex("\\n{3,}"), "\\n\\n").trim(), diary, tools)
    }

    fun runToolLoop(
        root: File,
        reply: String,
        messages: JSONArray,
        meta: JSONObject? = null,
        performTool: ((String, JSONObject) -> Pair<Boolean, String>)? = null,
        requestNext: (JSONArray) -> String?
    ): String {
        var current = reply
        var hops = 0
        fun finish(value: String): String {
            if (hops > 0) EmotionEngine.onActiveTask(root)
            return value
        }
        while (true) {
            val action = ChatEngine.parseAction(current) ?: return finish(current)
            if (hops >= 64) return finish(
                "Не удалось завершить цепочку действий: достигнут лимит 64 последовательных вызовов."
            )
            val name = action.optString("action")
            val params = JSONObject(action.toString()).apply { remove("action") }
            FileManager.appendEvent(root, SecretRedactor.redactEvent(
                JSONObject().put("type", "tool_call").put("action", name).put("params", params)
            ))
            val result = performTool?.invoke(name, params) ?: executeTool(root, name, params)
            // Was a bare 4000-char cut with no marker preservation, and a document page is 6000 —
            // so the diary and initiative paths got silent mid-text truncation of a memory she
            // then reasoned from. Same treatment as the chat path.
            val safeText = ChatEngine.trimKeepingMarker(SecretRedactor.redactText(result.second))
            FileManager.appendEvent(root, SecretRedactor.redactEvent(
                JSONObject().put("type", "tool_result").put("action", name)
                    .put("ok", result.first).put("text", safeText)
            ))
            messages.put(JSONObject().put("role", "user")
                .put("content", "[ИНСТРУМЕНТ ${name}]: ${safeText}"))
            if (!result.first) {
                messages.put(JSONObject().put("role", "system").put("content",
                    "Инструмент ${name} вернул ошибку или пустой результат. НЕ утверждай, что действие выполнено успешно. Скажи честно, что не удалось, и предложи альтернативу."))
            }
            hops++
            if (hops == 64) messages.put(JSONObject().put("role", "system")
                .put("content", "Лимит инструментов исчерпан — ответь обычной прозой"))
            current = requestNext(messages) ?: return finish("Не удалось получить ответ после выполнения инструмента.")
        }
    }

    fun executeTool(root: File, action: String, params: JSONObject): Pair<Boolean, String> =
        ChatEngine.executeTool(root, action, params)

    internal fun diaryActionText(raw: String): String? {
        val action = parseJsonAction(raw) ?: return null
        if (action.optString("action") !in setOf("write_diary", "diary")) return null
        return action.optString("text").trim().takeIf { it.isNotBlank() }?.let { SecretRedactor.redactText(it) }
    }

    fun unwrapDiaryReply(
        root: File,
        initialReply: String,
        messages: JSONArray,
        meta: JSONObject? = null,
        requestNext: (JSONArray) -> String?
    ): String {
        var reply = initialReply
        var checks = 0
        var toolLoopRun = false
        while (checks < 3) {
            val json = parseJsonAction(reply) ?: return reply.trim()
            val action = json.optString("action")
            if (action in allowed && ChatEngine.parseAction(reply) != null && !toolLoopRun) {
                toolLoopRun = true
                reply = runToolLoop(root, reply, messages, meta, requestNext = requestNext)
            } else if (action == "write_diary" || action == "diary") {
                return diaryActionText(reply).orEmpty()
            } else if (json.has("text") && json.opt("text") is String) {
                return SecretRedactor.redactText(json.optString("text").trim())
            } else {
                messages.put(JSONObject().put("role", "system")
                    .put("content", "Предыдущий ответ не является допустимым инструментом. Напиши только обычную русскую прозу, без JSON и без выдуманных action."))
                reply = requestNext(messages) ?: return ""
            }
            checks++
        }
        return if (parseJsonAction(reply) == null) reply.trim() else ""
    }

    internal fun isActionJson(raw: String): Boolean = parseJsonAction(raw) != null

    internal fun isJsonOnly(raw: String): Boolean {
        val trimmed = raw.trim()
        val candidate = if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            trimmed.substringAfter('\n', "").substringBeforeLast("```").trim()
                .removePrefix("json").trim()
        } else trimmed
        if (!candidate.startsWith("{") && !candidate.startsWith("[")) return false
        return runCatching { JSONObject(candidate); true }.getOrElse {
            runCatching { JSONArray(candidate); true }.getOrDefault(false)
        }
    }

    /** Visible prose only; JSON-only/action-only responses are not user-facing answers. */
    internal fun proseCandidate(raw: String): String? {
        if (raw.isBlank()) return null
        val cleaned = stripActions(raw).first.trim()
        if (cleaned.isBlank() || isActionJson(cleaned) || isJsonOnly(cleaned)) return null
        return cleaned
    }

    private fun parseJsonAction(raw: String): JSONObject? {
        val trimmed = raw.trim()
        val fenced = Regex("(?is)^\\x60\\x60\\x60(?:json)?\\s*(\\{.*\\})\\s*\\x60\\x60\\x60$").find(trimmed)
        val text = fenced?.groupValues?.get(1) ?: trimmed.takeIf { it.startsWith("{") && it.endsWith("}") } ?: return null
        return runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.has("action") }
    }
}
