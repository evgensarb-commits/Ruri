package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Shared action protocol and prose guard for interactive and autonomous model turns. */
object ToolLoop {
    private val allowed = setOf("search", "list_shelf", "read_shelf", "recall", "remember_fact", "read_url")

    private val actionBlock = Regex("\\{[^{}]*?\\\"action\\\"\\s*:\\s*\\\"[a-z_]+\\\"[^{}]*?\\}")

    fun stripActions(reply: String): Triple<String, List<String>, List<JSONObject>> {
        val diary = mutableListOf<String>(); val tools = mutableListOf<JSONObject>()
        var cleaned = reply
        for (match in actionBlock.findAll(reply)) {
            val action = runCatching { JSONObject(match.value) }.getOrNull() ?: continue
            when (action.optString("action")) {
                "write_diary", "diary" -> action.optString("text").trim().takeIf { it.isNotBlank() }?.let(diary::add)
                "search", "list_shelf", "read_shelf", "recall", "remember_fact", "read_url" -> tools.add(action)
                else -> Unit
            }
            cleaned = cleaned.replace(match.value, "")
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
        while (true) {
            val action = ChatEngine.parseAction(current) ?: return current
            if (hops >= 3) return current
            val name = action.optString("action")
            val params = JSONObject(action.toString()).apply { remove("action") }
            FileManager.appendEvent(root, SecretRedactor.redactEvent(
                JSONObject().put("type", "tool_call").put("action", name).put("params", params)
            ))
            val result = performTool?.invoke(name, params) ?: executeTool(root, name, params)
            if (name == "search") {
                ChatEngine.captureSearchUrls(result.second)
                if (meta != null) {
                    val urls = JSONArray()
                    runCatching {
                        val results = JSONObject(result.second).optJSONArray("results") ?: JSONArray()
                        val seen = LinkedHashSet<String>()
                        for (i in 0 until results.length()) {
                            val url = results.optJSONObject(i)?.optString("url").orEmpty()
                            if ((url.startsWith("https://") || url.startsWith("http://")) && seen.add(url)) {
                                urls.put(url)
                                if (urls.length() >= 20) break
                            }
                        }
                    }
                    meta.put("search_ok", result.first)
                    meta.put("source_urls", urls)
                }
            }
            val safeText = SecretRedactor.redactText(result.second).take(4000)
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
        meta: JSONObject? = null,
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
                reply = runToolLoop(root, reply, messages, meta, requestNext = requestNext)
            } else if (action == "write_diary" || action == "diary") {
                return reply.trim()
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
