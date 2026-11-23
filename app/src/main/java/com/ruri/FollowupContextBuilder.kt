package com.ruri

import org.json.JSONArray
import org.json.JSONObject

/**
 * Experimental follow-up context candidate. This is deliberately not wired
 * into ChatEngine or Thinker.
 *
 * Free-form chat messages have no dependency metadata, so prior user messages
 * are retained by default. A caller may mark a non-final, non-tool user message
 * as discardable only when it can prove that message is redundant.
 */
object FollowupContextBuilder {
    const val DISCARDABLE_FIELD = "_ruri_followup_discardable"

    fun lean(full: JSONArray): JSONArray {
        val out = JSONArray()
        val lastIndex = full.length() - 1
        for (index in 0 until full.length()) {
            val original = full.optJSONObject(index) ?: continue
            val role = original.optString("role")
            val content = original.optString("content")
            val isToolResult = role == "user" && content.startsWith("[ИНСТРУМЕНТ")
            val protected = index == 0 || index == lastIndex ||
                role == "system" || role == "assistant" || isToolResult
            val explicitlyDiscardable = original.optBoolean(DISCARDABLE_FIELD, false)
            if (explicitlyDiscardable && role == "user" && !protected) continue

            val copy = JSONObject(original.toString())
            copy.remove(DISCARDABLE_FIELD)
            out.put(copy)
        }
        return out
    }

    fun chars(messages: JSONArray): Int =
        (0 until messages.length()).sumOf { messages.optJSONObject(it)?.optString("content").orEmpty().length }
}
