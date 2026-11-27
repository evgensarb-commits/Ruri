package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object Diary {
    fun norm(s: String) = s.lowercase().replace(Regex("[^a-zа-яё0-9 ]"), " ").trim()
    private fun words(s: String) = norm(s).split(" ").filter { it.isNotBlank() }.toSet()

    fun isDuplicate(root: File, text: String, kind: String = "thought"): Boolean {
        val entries = runCatching { JSONObject(FileManager.read(root, "memory/L2_diary.json")).optJSONArray("entries") }.getOrNull() ?: return false
        val n = norm(text); val nw = words(text)
        val start = maxOf(0, entries.length() - 3)
        for (i in start until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (entry.optString("kind", "thought") != kind) continue
            val old = entry.optString("text"); val on = norm(old); val ow = words(old)
            if (on == n || on.startsWith(n.take(80)) || n.startsWith(on.take(80))) return true
            val jac = if (nw.isEmpty() || ow.isEmpty()) 0.0 else nw.intersect(ow).size.toDouble() / nw.union(ow).size
            if (jac > 0.6) return true
        }
        return false
    }

    @Synchronized
    fun append(
        root: File,
        text: String,
        trigger: String,
        emotion: JSONObject?,
        kind: String = "thought",
        sourceIds: Collection<String> = emptyList()
    ) {
        val mirrorSaid = runCatching { JSONObject(FileManager.read(root, "core/config.json")).optBoolean("diary_mirror_said", false) }.getOrElse { false }
        if (kind != "thought" && !(kind == "said" && mirrorSaid)) return
        val t = SecretRedactor.redactText(text.trim())
        if (t.isBlank() || isDuplicate(root, t, kind)) return
        val raw = FileManager.read(root, "memory/L2_diary.json")
        val d = if (raw.isBlank()) {
            JSONObject().put("entries", JSONArray())
        } else {
            // A non-blank but unparsable diary must not be replaced by a single new entry.
            runCatching { JSONObject(raw) }.getOrNull() ?: run {
                runCatching {
                    FileManager.write(
                        root,
                        "backup/unreadable_${System.currentTimeMillis()}_L2_diary.json",
                        raw
                    )
                }
                android.util.Log.w("Ruri", "L2 unreadable; diary entry skipped to protect memory")
                return
            }
        }
        val entries = d.optJSONArray("entries") ?: JSONArray()
        val total = maxOf(d.optLong("total_entries", entries.length().toLong()), entries.length().toLong()) + 1
        // An entry is a claim like any other: it names the raw events it rests on. Without them it
        // is marked as an assumption, which is what stops the loop of her confirming her own words.
        val verified = Provenance.verifiedSources(root, sourceIds)
        val id = UUID.randomUUID().toString()
        val e = JSONObject().put("id", id).put("sequence", total)
            .put("ts", System.currentTimeMillis()).put("text", t).put("trigger", trigger).put("kind", kind)
            .put("source_event_ids", JSONArray(verified))
            .put("origin", "diary")
            .put("status", Provenance.statusFor(verified))
            .put("confirmations", if (verified.isEmpty()) 0 else 1)
        if (emotion != null) e.put("emotion_before", emotion.optJSONObject("before"))
            .put("emotion_delta", emotion.optJSONObject("delta"))
            .put("emotion_after", emotion.optJSONObject("after"))
            .put("mood_label", emotion.optString("mood"))
        if (verified.isNotEmpty()) {
            // Journal the link so the trace is visible from L0 too, not only inside L2.
            runCatching {
                FileManager.appendEvent(root, JSONObject()
                    .put("type", "diary_linked")
                    .put("entry_id", id)
                    .put("source_event_ids", JSONArray(verified)))
            }
        }
        entries.put(e)
        if (entries.length() > CAPACITY) {
            // Eviction by weight, recorded: an entry must never vanish without a trace.
            MemoryWeight.trim(root, "L2", entries, CAPACITY)
        }
        d.put("entries", entries).put("total_entries", total).put("updated_at", System.currentTimeMillis())
        FileManager.write(root, "memory/L2_diary.json", d.toString(2))
    }

    /** Bounded by weight rather than by silently dropping the oldest entry. */
    private const val CAPACITY = 500
}
