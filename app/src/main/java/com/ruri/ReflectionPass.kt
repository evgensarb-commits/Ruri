package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Her own reflection pass: read a slice of the raw journal, derive candidates, record them with
 * provenance, and stop when the budget runs out.
 *
 * Budgets are the point. A pass that may read without limit becomes self-rumination: it re-reads
 * its own conclusions, confirms them, writes more, and never gives the phone back. So a pass
 * declares how many events it may read and how many claims it may write, and when either runs
 * out it stops until the next scheduler window.
 */
object ReflectionPass {

    private const val STATE_PATH = "memory/reflection_state.json"

    /** How many raw events one pass may read. */
    const val READ_BUDGET = 60

    /** How many claims one pass may write (facts + diary together). */
    const val WRITE_BUDGET = 8

    /** Hard cap on journal bytes read per pass, so a huge journal cannot stall a wake. */
    private const val BYTE_BUDGET = 256 * 1024

    data class PassResult(
        val readEvents: Int,
        val written: Int,
        val skipped: Int,
        val nextOffset: Long,
        val exhausted: Boolean,
        val reason: String
    )

    fun cursor(root: File): Long = runCatching {
        JSONObject(FileManager.read(root, STATE_PATH)).optLong("cursor", 0L)
    }.getOrDefault(0L)

    private fun setCursor(root: File, offset: Long, reads: Int, writes: Int) {
        runCatching {
            val state = runCatching { JSONObject(FileManager.read(root, STATE_PATH)) }
                .getOrElse { JSONObject() }
            FileManager.write(root, STATE_PATH, state
                .put("schema_version", 1)
                .put("cursor", offset)
                .put("last_pass_at", System.currentTimeMillis())
                .put("last_read_events", reads)
                .put("last_written", writes)
                .toString(2))
        }
    }

    /**
     * Runs one bounded pass.
     *
     * The derivation itself is delegated: this object enforces the budget and the provenance
     * rule, while [derive] decides what the events mean. Keeping those separate means the limits
     * cannot be forgotten by whichever model or heuristic is plugged in.
     *
     * @param derive receives the window and may return candidates, each requiring `text` and
     *        `source_ids`; anything without sources is downgraded to an assumption.
     */
    fun run(
        root: File,
        now: Long = System.currentTimeMillis(),
        derive: (List<RawLog.Entry>) -> List<JSONObject>
    ): PassResult {
        val start = cursor(root)
        val (window, nextOffset) = RawLog.read(
            root = root,
            afterOffset = start,
            limit = READ_BUDGET
        )
        if (window.isEmpty()) {
            setCursor(root, nextOffset, 0, 0)
            return PassResult(0, 0, 0, nextOffset, false, "нет новых событий")
        }

        // Stop before the window grows past the byte budget, keeping the cursor consistent.
        var bytes = 0
        val bounded = ArrayList<RawLog.Entry>()
        for (entry in window) {
            if (bytes + entry.length > BYTE_BUDGET) break
            bytes += entry.length
            bounded.add(entry)
        }

        val candidates = runCatching { derive(bounded) }.getOrDefault(emptyList())
        var written = 0
        var skipped = 0
        for (candidate in candidates) {
            if (written >= WRITE_BUDGET) { skipped++; continue }
            if (store(root, candidate, now)) written++ else skipped++
        }

        setCursor(root, nextOffset, bounded.size, written)
        return PassResult(
            readEvents = bounded.size,
            written = written,
            skipped = skipped,
            nextOffset = nextOffset,
            exhausted = written >= WRITE_BUDGET || skipped > 0,
            reason = if (written >= WRITE_BUDGET) "бюджет записей исчерпан" else "проход завершён"
        )
    }

    /**
     * Stores one candidate with its provenance.
     *
     * Sources are verified against the journal first: an id that does not exist means the model
     * invented its evidence, and such a claim is not allowed to look confirmed.
     */
    internal fun store(root: File, candidate: JSONObject, now: Long = System.currentTimeMillis()): Boolean {
        val text = SecretRedactor.redactText(candidate.optString("text").trim()).take(300)
        if (text.length < 5) return false

        val rawSources = ArrayList<String>()
        val incoming = candidate.optJSONArray("source_ids") ?: JSONArray()
        for (i in 0 until incoming.length()) {
            val id = incoming.optString(i).trim()
            if (id.isNotBlank()) rawSources.add(id)
        }
        val verified = Provenance.verifiedSources(root, rawSources)
        val origin = if (verified.isEmpty()) "assumption" else candidate.optString("origin", "reflection")

        val facts = readFacts(root)
        // Duplicate: strengthen the existing claim instead of storing a twin.
        for (i in 0 until facts.length()) {
            val existing = facts.optJSONObject(i) ?: continue
            if (normalize(existing.optString("text")) != normalize(text)) continue
            if (existing.optString("status") == Provenance.STATUS_REJECTED) return false
            existing.put("confirmations", existing.optInt("confirmations", 1) + 1)
            existing.put("last_confirmed_at", now)
            existing.put("confidence", minOf(1.0, existing.optDouble("confidence", 0.5) + 0.1))
            val merged = mergeSources(existing.optJSONArray("source_event_ids"), verified)
            existing.put("source_event_ids", merged)
            if (existing.optString("status") == Provenance.STATUS_ASSUMPTION && verified.isNotEmpty()) {
                existing.put("status", Provenance.STATUS_ASSERTED)
            }
            writeFacts(root, facts)
            return true
        }

        val id = "refl-" + java.util.UUID.randomUUID().toString()
        MakeRoomOrTrim(root, id, facts)
        facts.put(JSONObject()
            .put("id", id)
            .put("text", text)
            .put("category", candidate.optString("category", "general").take(40))
            .put("source_event_ids", JSONArray(verified))
            .put("origin", origin)
            .put("confidence", candidate.optDouble("confidence", if (verified.isEmpty()) 0.25 else 0.6))
            .put("status", Provenance.statusFor(verified))
            .put("confirmations", if (verified.isEmpty()) 0 else 1)
            .put("created_at", now)
            .put("updated_at", now)
            .put("last_confirmed_at", now))
        writeFacts(root, facts)
        return true
    }

    private fun MakeRoomOrTrim(root: File, incomingId: String, facts: JSONArray) {
        if (facts.length() >= FACT_CAP) {
            MemoryWeight.makeRoom(root, "L1", facts, FACT_CAP, incomingId)
        }
    }

    private fun readFacts(root: File): JSONArray {
        val file = runCatching { JSONObject(FileManager.read(root, "memory/L1_facts.json")) }
            .getOrElse { JSONObject() }
        return file.optJSONArray("facts") ?: JSONArray().also { file.put("facts", it) }
    }

    private fun writeFacts(root: File, facts: JSONArray) {
        val file = runCatching { JSONObject(FileManager.read(root, "memory/L1_facts.json")) }
            .getOrElse { JSONObject() }
        file.put("facts", facts)
        file.put("updated_at", System.currentTimeMillis())
        FileManager.write(root, "memory/L1_facts.json", file.toString(2))
    }

    private fun mergeSources(existing: JSONArray?, added: List<String>): JSONArray {
        val out = LinkedHashSet<String>()
        if (existing != null) for (i in 0 until existing.length()) out.add(existing.optString(i))
        out.addAll(added)
        val array = JSONArray()
        out.filter { it.isNotBlank() }.forEach { array.put(it) }
        return array
    }

    internal fun normalize(value: String): String =
        value.lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** Facts are bounded by weight now, not by a silent refusal to add anything. */
    const val FACT_CAP = 500
}
