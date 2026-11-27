package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Provenance for everything she concludes about the Commander or herself.
 *
 * The rule the Commander set: nothing may be stored as a fact unless it names the raw L0
 * events it came from, and a challenge never deletes — a rejected claim stays with its original
 * sources as a record of the extractor's mistake.
 *
 * This object owns only the status vocabulary and validation. The stores (L1 facts, L2 diary)
 * call into it, so the rules cannot drift apart between them.
 */
object Provenance {

    const val STATUS_ASSERTED = "asserted"
    const val STATUS_ASSUMPTION = "assumption"
    const val STATUS_CHALLENGED = "challenged"
    const val STATUS_REJECTED = "rejected"

    val ALL = setOf(STATUS_ASSERTED, STATUS_ASSUMPTION, STATUS_CHALLENGED, STATUS_REJECTED)

    /** A claim without sources is an assumption, never a fact. */
    fun statusFor(sources: Collection<String>): String =
        if (sources.isEmpty()) STATUS_ASSUMPTION else STATUS_ASSERTED

    /**
     * Keeps only ids that actually exist in the journal.
     *
     * A model can invent a plausible-looking id; storing it would create a chain that points at
     * nothing and looks authoritative. Dropping unknown ids is what makes the trace honest.
     */
    fun verifiedSources(root: File, ids: Collection<String>): List<String> =
        RawLog.existingIds(root, ids).toList()

    /** Builds the provenance block stored next to a claim. */
    fun block(sources: List<String>, origin: String, confidence: Double): JSONObject =
        JSONObject()
            .put("source_event_ids", JSONArray(sources))
            .put("origin", origin)
            .put("confidence", confidence.coerceIn(0.0, 1.0))
            .put("status", statusFor(sources))
            .put("confirmations", if (sources.isEmpty()) 0 else 1)
            .put("created_at", System.currentTimeMillis())

    /**
     * Records that the Commander disputed a claim. Nothing is removed: the status changes and
     * the challenge itself is journaled, so both sides stay visible.
     */
    fun challenge(root: File, store: String, claimId: String, reason: String = ""): Boolean {
        val path = storePath(store) ?: return false
        val file = runCatching { JSONObject(FileManager.read(root, path)) }.getOrNull() ?: return false
        val array = file.optJSONArray(if (store == "L2") "entries" else "facts") ?: return false
        var changed = false
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("id") != claimId) continue
            item.put("status", STATUS_CHALLENGED)
            item.put("challenged_at", System.currentTimeMillis())
            if (reason.isNotBlank()) item.put("challenge_reason", reason.take(300))
            changed = true
            break
        }
        if (!changed) return false
        FileManager.write(root, path, file.toString(2))
        FileManager.appendEvent(root, JSONObject()
            .put("type", "claim_challenged")
            .put("store", store)
            .put("claim_id", claimId)
            .put("status", STATUS_CHALLENGED)
            .put("reason", SecretRedactor.redactText(reason).take(300)))
        return true
    }

    /**
     * Final verdict on a challenged claim.
     *
     * `rejected` is terminal in the sense that it is never deleted and never silently revived:
     * the entry keeps its sources and the rejection reason, which is exactly the trail that
     * documents what the extractor got wrong.
     */
    fun reject(root: File, store: String, claimId: String, reason: String = ""): Boolean {
        val path = storePath(store) ?: return false
        val file = runCatching { JSONObject(FileManager.read(root, path)) }.getOrNull() ?: return false
        val array = file.optJSONArray(if (store == "L2") "entries" else "facts") ?: return false
        var changed = false
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("id") != claimId) continue
            item.put("status", STATUS_REJECTED)
            item.put("rejected_at", System.currentTimeMillis())
            if (reason.isNotBlank()) item.put("reject_reason", SecretRedactor.redactText(reason).take(300))
            // Sources are kept on purpose: deleting them would erase the evidence of the error.
            changed = true
            break
        }
        if (!changed) return false
        FileManager.write(root, path, file.toString(2))
        FileManager.appendEvent(root, JSONObject()
            .put("type", "claim_rejected")
            .put("store", store)
            .put("claim_id", claimId)
            .put("status", STATUS_REJECTED))
        return true
    }

    /** Turns a challenged claim back into an ordinary one after a fresh, sourced derivation. */
    fun restore(root: File, store: String, claimId: String, newSources: Collection<String>): Boolean {
        val path = storePath(store) ?: return false
        val file = runCatching { JSONObject(FileManager.read(root, path)) }.getOrNull() ?: return false
        val array = file.optJSONArray(if (store == "L2") "entries" else "facts") ?: return false
        val verified = verifiedSources(root, newSources)
        if (verified.isEmpty()) return false
        var changed = false
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("id") != claimId) continue
            item.put("status", STATUS_ASSERTED)
            item.put("source_event_ids", JSONArray(verified))
            item.put("restored_at", System.currentTimeMillis())
            changed = true
            break
        }
        if (!changed) return false
        FileManager.write(root, path, file.toString(2))
        return true
    }

    /** Claims awaiting a human verdict, newest first. */
    fun pending(root: File): JSONArray {
        val out = JSONArray()
        for (store in listOf("L1", "L2")) {
            val path = storePath(store) ?: continue
            val file = runCatching { JSONObject(FileManager.read(root, path)) }.getOrNull() ?: continue
            val array = file.optJSONArray(if (store == "L2") "entries" else "facts") ?: continue
            for (i in array.length() - 1 downTo 0) {
                val item = array.optJSONObject(i) ?: continue
                val status = item.optString("status", STATUS_ASSERTED)
                if (status != STATUS_CHALLENGED) continue
                out.put(JSONObject(item.toString()).put("store", store))
            }
        }
        return out
    }

    private fun storePath(store: String): String? = when (store) {
        "L1" -> "memory/L1_facts.json"
        "L2" -> "memory/L2_diary.json"
        else -> null
    }
}
