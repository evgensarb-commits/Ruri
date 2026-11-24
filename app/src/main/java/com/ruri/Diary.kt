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
    fun append(root: File, text: String, trigger: String, emotion: JSONObject?, kind: String = "thought") {
        val mirrorSaid = runCatching { JSONObject(FileManager.read(root, "core/config.json")).optBoolean("diary_mirror_said", false) }.getOrElse { false }
        if (kind != "thought" && !(kind == "said" && mirrorSaid)) return
        val t = SecretRedactor.redactText(text.trim())
        if (t.isBlank() || isDuplicate(root, t, kind)) return
        val d = runCatching { JSONObject(FileManager.read(root, "memory/L2_diary.json")) }
            .getOrElse { JSONObject().put("entries", JSONArray()) }
        val entries = d.optJSONArray("entries") ?: JSONArray()
        val total = maxOf(d.optLong("total_entries", entries.length().toLong()), entries.length().toLong()) + 1
        val e = JSONObject().put("id", UUID.randomUUID().toString()).put("sequence", total)
            .put("ts", System.currentTimeMillis()).put("text", t).put("trigger", trigger).put("kind", kind)
        if (emotion != null) e.put("emotion_before", emotion.optJSONObject("before"))
            .put("emotion_delta", emotion.optJSONObject("delta"))
            .put("emotion_after", emotion.optJSONObject("after"))
            .put("mood_label", emotion.optString("mood"))
        entries.put(e); while (entries.length() > 500) entries.remove(0)
        d.put("entries", entries).put("total_entries", total).put("updated_at", System.currentTimeMillis())
        FileManager.write(root, "memory/L2_diary.json", d.toString(2))
    }
}
