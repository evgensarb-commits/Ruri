package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Which memory entry survives when a store is full.
 *
 * The old rule was a bare cap: at 500 facts the next fact was dropped and the oldest survived,
 * because nothing ever chose between them. That is backwards for a companion — the freshest
 * important thing lost to a stale one. Weight decides, and every eviction leaves a record.
 */
object MemoryWeight {

    /**
     * A rejected claim is worthless, and a challenged one is not evidence.
     *
     * Without this, re-deriving a rejected claim refreshed its `last_confirmed_at`, so it grew
     * heavier over time and evicted live memories at the cap — dead weight crowding out real
     * memory.
     */
    private fun statusFactor(status: String): Double = when (status) {
        "rejected" -> 0.0
        "challenged" -> 0.15
        "assumption" -> 0.5
        else -> 1.0
    }

    /** How much a source counts. A spoken message is stronger evidence than a silent thought. */
    private fun sourceFactor(origin: String): Double = when {
        origin.startsWith("commander") || origin == "self_reported" -> 1.0
        origin == "automatic_extraction" -> 0.8
        origin == "diary" -> 0.5
        origin == "assumption" -> 0.3
        else -> 0.6
    }

    /**
     * Weight = freshness × confirmations × source factor.
     *
     * Freshness halves every [HALF_LIFE_DAYS] days without confirmation, so old-but-true things
     * fade slowly instead of vanishing, and recently mentioned things stay reachable.
     */
    fun weight(
        createdAt: Long,
        lastConfirmedAt: Long,
        confirmations: Int,
        origin: String,
        status: String = "asserted",
        now: Long = System.currentTimeMillis()
    ): Double {
        val reference = if (lastConfirmedAt > 0L) lastConfirmedAt else createdAt
        val ageDays = if (reference <= 0L) 3650.0
        else ((now - reference).coerceAtLeast(0L).toDouble() / TimeUnit.DAYS.toMillis(1))
        val freshness = Math.pow(0.5, ageDays / HALF_LIFE_DAYS)
        val confirmation = (1.0 + Math.log1p(confirmations.coerceAtLeast(0).toDouble()))
        return freshness * confirmation * sourceFactor(origin) * statusFactor(status)
    }

    fun weightOf(item: JSONObject, now: Long = System.currentTimeMillis()): Double = weight(
        createdAt = item.optLong("created_at", item.optLong("ts", 0L)),
        lastConfirmedAt = item.optLong("last_confirmed_at", 0L),
        confirmations = item.optInt("confirmations", 1),
        origin = item.optString("origin", "unknown"),
        status = item.optString("status", "asserted"),
        now = now
    )

    /** Cost of keeping one entry, so eviction can explain itself in bytes. */
    fun bytesOf(item: JSONObject): Int = item.toString().toByteArray(Charsets.UTF_8).size

    /**
     * Makes room for [incoming] by evicting the lowest-weight entries.
     *
     * Never evicts the incoming item to satisfy the limit: that is the exact behaviour that was
     * forbidden. If the incoming item is the weakest thing in the store it still goes in, and
     * the caller is told what was dropped.
     *
     * @return ids that were evicted, weakest first
     */
    fun makeRoom(
        root: File,
        store: String,
        array: JSONArray,
        cap: Int,
        incomingId: String,
        now: Long = System.currentTimeMillis()
    ): List<String> {
        if (array.length() < cap) return emptyList()
        // Rank every entry except the newcomer, weakest first.
        val ranked = (0 until array.length())
            .mapNotNull { array.optJSONObject(it) }
            .filter { it.optString("id") != incomingId }
            .map { Triple(it.optString("id"), weightOf(it, now), bytesOf(it)) }
            .sortedBy { it.second }

        val evicted = ArrayList<String>()
        var remaining = array.length()
        for ((id, weight, bytes) in ranked) {
            if (remaining < cap) break
            evicted.add(id)
            remaining--
            appendEviction(root, store, id, weight, bytes, incomingId, now)
            removeById(array, id)
        }
        return evicted
    }

    /** Trims a store to [cap] by weight, reporting everything it dropped. */
    fun trim(root: File, store: String, array: JSONArray, cap: Int, now: Long = System.currentTimeMillis()): List<String> {
        val evicted = ArrayList<String>()
        if (array.length() <= cap) return evicted
        val ranked = (0 until array.length())
            .mapNotNull { array.optJSONObject(it) }
            .map { Triple(it.optString("id"), weightOf(it, now), bytesOf(it)) }
            .sortedBy { it.second }
        for ((id, weight, bytes) in ranked) {
            if (array.length() <= cap) break
            evicted.add(id)
            appendEviction(root, store, id, weight, bytes, "", now)
            removeById(array, id)
        }
        return evicted
    }

    /** `eviction_evt` is the audit trail: no entry may disappear without one. */
    private fun appendEviction(
        root: File,
        store: String,
        evictedId: String,
        weight: Double,
        bytes: Int,
        replacedBy: String,
        now: Long
    ) {
        runCatching {
            FileManager.appendEvent(root, JSONObject()
                .put("type", "eviction_evt")
                .put("store", store)
                .put("evicted_id", evictedId)
                .put("weight", String.format(java.util.Locale.US, "%.6f", weight))
                .put("bytes", bytes)
                .put("replaced_by", replacedBy)
                .put("ts", now))
        }
    }

    private fun removeById(array: JSONArray, id: String) {
        for (i in 0 until array.length()) {
            if (array.optJSONObject(i)?.optString("id") == id) {
                array.remove(i)
                return
            }
        }
    }

    /** Freshness half-life. Two weeks felt too short for a person, three months too long. */
    private const val HALF_LIFE_DAYS = 45.0
}
