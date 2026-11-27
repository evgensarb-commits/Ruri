package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Read-only window over the raw journal (L0).
 *
 * L0 is append-only and must never be rewritten, so the way to page through it is a byte
 * cursor, not a full scan per page: the journal grows without bound and re-reading it from the
 * start on every page would stall on a real phone.
 *
 * The index is a cache, not a source of truth. If it disagrees with the file (the file grew
 * while the app was closed, or it was restored from a backup) the index is rebuilt from the
 * cursor instead of trusting stale bytes.
 */
object RawLog {

    private const val SCHEMA = 1

    /**
     * Everything she may ask for by name.
     *
     * This is the union of both groups below. It exists only for validation; it must NOT be the
     * default filter, which is [CONVERSATIONAL_TYPES].
     */
    val READABLE_TYPES = setOf(
        "message", "tool_call", "tool_result", "wake", "emotion_delta",
        "phone_notification", "facts_extracted", "initiative_gate_evaluated",
        "initiative_ignored", "initiative_vetoed", "nsfw_refusal", "error",
        "eviction_evt", "diary_linked", "claim_challenged", "claim_rejected",
        "thought_lost", "self_description_changed"
    )

    /**
     * What "the journal" means by default: things that happened to her and to the Commander.
     *
     * Eleven of the eighteen readable types are bookkeeping — tool traffic, wakes, gate
     * evaluations, emotion arithmetic. Defaulting to all of them meant a 30-entry page was
     * consumed by machinery, and she never saw a single line of the actual conversation.
     * Technical types stay available on explicit request, because sometimes she needs them.
     */
    val CONVERSATIONAL_TYPES = setOf(
        "message", "phone_notification", "diary_linked", "nsfw_refusal",
        "claim_challenged", "claim_rejected", "self_description_changed"
    )

    /** Types that describe machinery rather than life. Requestable, never default. */
    val TECHNICAL_TYPES = READABLE_TYPES - CONVERSATIONAL_TYPES

    /** One decoded journal line plus where it lives in the file. */
    data class Entry(
        val offset: Long,
        val length: Int,
        val id: String,
        val type: String,
        val who: String,
        val ts: Long,
        val json: JSONObject
    )

    /**
     * Reads a window of events without loading the whole journal.
     *
     * @param afterOffset exclusive byte cursor, 0 to start at the beginning
     * @param limit hard cap on returned entries
     * @param types optional filter; empty means everything readable
     */
    fun read(
        root: File,
        afterOffset: Long = 0L,
        limit: Int = 40,
        types: Set<String> = emptySet(),
        fromTs: Long = 0L,
        toTs: Long = 0L
    ): Pair<List<Entry>, Long> {
        val bounded = limit.coerceIn(1, 200)
        val target = File(root, "memory/L0_raw.jsonl")
        if (!target.isFile) return emptyList<Entry>() to afterOffset

        // No explicit request means "show me life, not machinery".
        val wanted = if (types.isEmpty()) CONVERSATIONAL_TYPES else types.intersect(READABLE_TYPES)
        if (wanted.isEmpty()) return emptyList<Entry>() to afterOffset

        val out = ArrayList<Entry>()
        var cursor = afterOffset.coerceAtLeast(0L)
        var nextCursor = cursor
        try {
            java.io.RandomAccessFile(target, "r").use { raf ->
                val length = raf.length()
                if (cursor > length) cursor = 0L
                raf.seek(cursor)
                while (out.size < bounded && cursor < length) {
                    val lineStart = cursor
                    val raw = raf.readLine() ?: break
                    // readLine() decodes as ISO-8859-1; the journal is UTF-8, so re-read the
                    // exact byte range and decode it properly. Cyrillic would be mojibake otherwise.
                    val lineLength = raw.toByteArray(Charsets.ISO_8859_1).size
                    // Advance past the terminator, but only when one is actually there:
                    // readLine() has already consumed it, so the byte is checked at its exact
                    // offset. Assuming a newline pushed the cursor past EOF on a torn read (an
                    // append in flight), and the next call then saw cursor > length and restarted
                    // from the beginning of the journal. Never advancing would loop forever, so
                    // the terminator length is measured rather than guessed.
                    val endOfLine = lineStart + lineLength
                    var terminator = 0
                    if (endOfLine < length) {
                        raf.seek(endOfLine)
                        val first = raf.read()
                        if (first == '\n'.code) {
                            terminator = 1
                        } else if (first == '\r'.code) {
                            terminator = 1
                            if (endOfLine + 1 < length) {
                                raf.seek(endOfLine + 1)
                                if (raf.read() == '\n'.code) terminator = 2
                            }
                        }
                    }
                    cursor = endOfLine + terminator
                    nextCursor = cursor
                    if (lineStart >= length) break

                    val bytes = ByteArray(lineLength)
                    raf.seek(lineStart)
                    raf.readFully(bytes)
                    raf.seek(cursor)

                    val text = bytes.toString(Charsets.UTF_8).trim()
                    if (text.isEmpty()) continue
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    val type = json.optString("type")
                    if (type !in wanted) continue
                    val ts = json.optLong("ts", json.optLong("timestamp", 0L))
                    if (fromTs > 0L && ts < fromTs) continue
                    if (toTs > 0L && ts > toTs) continue
                    out.add(
                        Entry(
                            offset = lineStart,
                            length = lineLength,
                            id = json.optString("id"),
                            type = type,
                            who = json.optString("who"),
                            ts = ts,
                            json = json
                        )
                    )
                }
            }
        } catch (_: Exception) {
            return out to nextCursor
        }
        return out to nextCursor
    }

    /** Total journal size, so she can tell how much is left. */
    fun sizeBytes(root: File): Long = File(root, "memory/L0_raw.jsonl").let {
        if (it.isFile) it.length() else 0L
    }

    /** Human-readable digest of one entry, trimmed so a page cannot flood the context. */
    fun describe(entry: Entry, maxChars: Int = 400): String {
        val text = entry.json.optString("text")
            .ifBlank { entry.json.optString("reason") }
            .ifBlank { entry.json.optString("snippet") }
            .ifBlank { entry.json.optJSONObject("params")?.toString().orEmpty() }
            .ifBlank { entry.json.optString("action") }
        val who = entry.who.ifBlank { entry.json.optString("source") }
        val stamp = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale("ru"))
            .format(java.util.Date(entry.ts))
        val head = "[${entry.id.take(14)}] $stamp $who ${entry.type}"
        return if (text.isBlank()) head else "$head: ${text.take(maxChars)}"
    }

    /**
     * Index snapshot: counts per type and the newest id of each type. Rebuilt on demand from
     * the file itself, never from assumptions, so it can never claim events that do not exist.
     */
    fun index(root: File): JSONObject {
        val target = File(root, "memory/L0_raw.jsonl")
        val result = JSONObject().put("schema_version", SCHEMA)
        if (!target.isFile) {
            return result.put("bytes", 0L).put("lines", 0).put("by_type", JSONObject())
        }
        val byType = JSONObject()
        var lines = 0
        var badLines = 0
        try {
            target.bufferedReader(Charsets.UTF_8).useLines { seq ->
                seq.forEach { line ->
                    if (line.isBlank()) return@forEach
                    lines++
                    val json = runCatching { JSONObject(line) }.getOrNull()
                    if (json == null) { badLines++; return@forEach }
                    val type = json.optString("type").ifBlank { "unknown" }
                    val bucket = byType.optJSONObject(type) ?: JSONObject()
                        .put("count", 0).put("last_id", "").put("last_ts", 0L)
                    bucket.put("count", bucket.optInt("count") + 1)
                    val ts = json.optLong("ts", json.optLong("timestamp", 0L))
                    if (ts >= bucket.optLong("last_ts")) {
                        bucket.put("last_ts", ts)
                        bucket.put("last_id", json.optString("id"))
                    }
                    byType.put(type, bucket)
                }
            }
        } catch (_: Exception) {
            return result.put("error", "не удалось прочитать журнал")
        }
        return result
            .put("bytes", target.length())
            .put("lines", lines)
            .put("malformed", badLines)
            .put("by_type", byType)
            .put("updated_at", System.currentTimeMillis())
    }

    /** Ids that exist in the journal. Used to validate provenance before anything is stored. */
    fun existingIds(root: File, ids: Collection<String>): Set<String> {
        val wanted = ids.filter { it.isNotBlank() }.toSet()
        if (wanted.isEmpty()) return emptySet()
        val found = HashSet<String>()
        val target = File(root, "memory/L0_raw.jsonl")
        if (!target.isFile) return found
        // A plain loop rather than useLines: the per-line skip form only skipped one line, so the
        // "early exit" re-read the entire journal on every provenance check.
        runCatching {
            target.bufferedReader(Charsets.UTF_8).use { reader ->
                while (found.size < wanted.size) {
                    val line = reader.readLine() ?: break
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    val id = json.optString("id")
                    if (id in wanted) found.add(id)
                }
            }
        }
        return found
    }
}
