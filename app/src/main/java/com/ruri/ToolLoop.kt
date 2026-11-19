package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Shared action protocol and prose guard for interactive and autonomous model turns. */
object ToolLoop {
    private val allowed = setOf("search", "list_shelf", "read_shelf", "recall", "remember_fact")

    fun runToolLoop(
        root: File,
        reply: String,
        messages: JSONArray,
        requestNext: (JSONArray) -> String?
    ): String {
        var current = reply
        var hops = 0
        while (true) {
            val action = ChatEngine.parseAction(current) ?: return current
            if (hops >= 3) return current
            val name = action.optString("action")
            val params = JSONObject(action.toString()).apply { remove("action") }
            FileManager.appendEvent(root, SecretRedactor.redactEvent(
                JSONObject().put("type", "tool_call").put("action", name).put("params", params)
            ))
            val result = executeTool(root, name, action)
            if (name == "search") ChatEngine.captureSearchUrls(result.second)
            val safeText = SecretRedactor.redactText(result.second).take(4000)
            FileManager.appendEvent(root, SecretRedactor.redactEvent(
                JSONObject().put("type", "tool_result").put("action", name)
                    .put("ok", result.first).put("text", safeText)
            ))
            messages.put(JSONObject().put("role", "user")
                .put("content", "[ИНСТРУМЕНТ ${name}]: ${safeText}"))
            hops++
            if (hops == 3) messages.put(JSONObject().put("role", "system")
                .put("content", "Лимит инструментов исчерпан — ответь обычной прозой"))
            current = requestNext(messages) ?: return "Не удалось получить ответ после выполнения инструмента."
        }
    }

    fun executeTool(root: File, action: String, params: JSONObject): Pair<Boolean, String> =
        ChatEngine.executeTool(root, action, params)

    fun unwrapDiaryReply(
        root: File,
        initialReply: String,
        messages: JSONArray,
        requestNext: (JSONArray) -> String?
    ): String {
        ChatEngine.clearSourceUrls()
        var reply = initialReply
        var checks = 0
        var toolLoopRun = false
        while (checks < 3) {
            val json = parseJsonAction(reply) ?: return reply.trim()
            val action = json.optString("action")
            if (action in allowed && ChatEngine.parseAction(reply) != null && !toolLoopRun) {
                toolLoopRun = true
                reply = runToolLoop(root, reply, messages, requestNext)
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

    private fun parseJsonAction(raw: String): JSONObject? {
        val trimmed = raw.trim()
        val fenced = Regex("(?is)^\\x60\\x60\\x60(?:json)?\\s*(\\{.*\\})\\s*\\x60\\x60\\x60$").find(trimmed)
        val text = fenced?.groupValues?.get(1) ?: trimmed.takeIf { it.startsWith("{") && it.endsWith("}") } ?: return null
        return runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.has("action") }
    }
}
