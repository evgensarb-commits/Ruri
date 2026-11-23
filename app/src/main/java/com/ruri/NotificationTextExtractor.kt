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
    private const val MAX_VISITED_VALUES = 500
    private const val MAX_ITEMS_PER_CONTAINER = 100
    private const val MAX_VALUE_CHARS = 2000

    fun extract(extras: Bundle, excludedKeys: Set<String> = emptySet()): String {
        val values = LinkedHashMap<String, Any?>()
        extras.keySet().asSequence().take(MAX_ITEMS_PER_CONTAINER).sorted().forEach { key ->
            if (key !in excludedKeys) values[key] = extras.get(key)
        }
        return extractFromValues(values)
    }

    internal fun extractFromValues(values: Map<String, Any?>): String {
        val collected = LinkedHashSet<String>()
        val visited = intArrayOf(0)
        values.entries.asSequence().take(MAX_ITEMS_PER_CONTAINER)
            .sortedBy { it.key }.forEach { (key, value) ->
                collect(key.take(120), value, collected, 0, visited)
            }
        return collected.joinToString("\n").take(MAX_NOTIFICATION_TEXT_CHARS)
    }

    private fun collect(
        label: String,
        value: Any?,
        collected: LinkedHashSet<String>,
        depth: Int,
        visited: IntArray
    ) {
        if (value == null || depth > MAX_NESTING_DEPTH || visited[0] >= MAX_VISITED_VALUES) return
        visited[0]++
        when (value) {
            is CharSequence -> {
                val text = value.toString().trim().take(MAX_VALUE_CHARS)
                if (text.isNotBlank()) collected.add(label + ": " + text)
            }
            is Bundle -> {
                value.keySet().asSequence().take(MAX_ITEMS_PER_CONTAINER).sorted().forEach { key ->
                    if (visited[0] < MAX_VISITED_VALUES) {
                        collect(label + "." + key.take(80), value.get(key), collected, depth + 1, visited)
                    }
                }
            }
            is Map<*, *> -> {
                value.entries.asSequence().take(MAX_ITEMS_PER_CONTAINER)
                    .sortedBy { it.key?.toString().orEmpty() }.forEach { entry ->
                        if (visited[0] < MAX_VISITED_VALUES) {
                            collect(label + "." + entry.key.toString().take(80), entry.value, collected, depth + 1, visited)
                        }
                    }
            }
            is Array<*> -> value.asSequence().take(MAX_ITEMS_PER_CONTAINER).forEachIndexed { index, item ->
                if (visited[0] < MAX_VISITED_VALUES) collect("$label[$index]", item, collected, depth + 1, visited)
            }
            is Iterable<*> -> value.asSequence().take(MAX_ITEMS_PER_CONTAINER).forEachIndexed { index, item ->
                if (visited[0] < MAX_VISITED_VALUES) collect("$label[$index]", item, collected, depth + 1, visited)
            }
        }
    }
}
