package com.ruri

import android.os.Bundle

/**
 * Extracts every text-like value exposed by a notification Bundle, including
 * expanded big text, text lines, and nested MessagingStyle message bundles.
 * Binary attachments and non-text actions are outside this text-only channel.
 */
object NotificationTextExtractor {
    private const val MAX_NOTIFICATION_TEXT_CHARS = 20000
    private const val MAX_NESTING_DEPTH = 7

    fun extract(extras: Bundle, excludedKeys: Set<String> = emptySet()): String {
        val values = LinkedHashMap<String, Any?>()
        extras.keySet().sorted().forEach { key ->
            if (key !in excludedKeys) values[key] = extras.get(key)
        }
        return extractFromValues(values)
    }

    internal fun extractFromValues(values: Map<String, Any?>): String {
        val collected = LinkedHashSet<String>()
        values.toSortedMap().forEach { (key, value) ->
            collect(key, value, collected, 0)
        }
        return collected.joinToString("\n").take(MAX_NOTIFICATION_TEXT_CHARS)
    }

    private fun collect(
        label: String,
        value: Any?,
        collected: LinkedHashSet<String>,
        depth: Int
    ) {
        if (value == null || depth > MAX_NESTING_DEPTH) return
        when (value) {
            is CharSequence -> {
                val text = value.toString().trim()
                if (text.isNotBlank()) collected.add(label + ": " + text)
            }
            is Bundle -> {
                value.keySet().sorted().forEach { key ->
                    collect(label + "." + key, value.get(key), collected, depth + 1)
                }
            }
            is Map<*, *> -> {
                value.entries.sortedBy { it.key?.toString().orEmpty() }.forEach { entry ->
                    collect(label + "." + entry.key, entry.value, collected, depth + 1)
                }
            }
            is Array<*> -> value.forEachIndexed { index, item ->
                collect(label + "[" + index + "]", item, collected, depth + 1)
            }
            is Iterable<*> -> value.forEachIndexed { index, item ->
                collect(label + "[" + index + "]", item, collected, depth + 1)
            }
        }
    }
}
