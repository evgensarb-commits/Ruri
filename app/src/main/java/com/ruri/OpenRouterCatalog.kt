package com.ruri

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenRouter's public model catalog, restricted to no-credit-purchase free variants.
 *
 * A model is eligible only when its ID ends in :free AND both published token
 * prices are explicitly zero. A zero-priced model without :free is not enough:
 * OpenRouter documents that some such models still require a funded account.
 * Candidate names are discovery hints, never a guarantee of uncensored behavior.
 */
internal object OpenRouterCatalog {
    private val adultAndRoleplay = Regex(
        "(?i)(nsfw|uncensored|abliterat|noromaid|lumimaid|mythomax|cydonia|rocinante|" +
            "unslopnemo|stheno|eva[-_ ]?llama|tiefighter|dark[-_ ]?idol|midnight[-_ ]?miqu|" +
            "magnum|euryale|pygmalion|airoboros|kunoichi|fimbulvetr|toppy|capybara|role[-_ ]?play|roleplay|erp|lewd|smut|erotic|adult|18[+]|no[-_ ]?filter|" +
            "not[-_ ]?safe[-_ ]?for[-_ ]?work|dolphin|venice[-_ ]?uncensored)"
    )

    data class CatalogResult(
        val models: JSONArray,
        val modelsSeen: Int,
        val candidates: Int,
        val freeVariants: Int,
        val zeroPriced: Int,
        val eligible: Int,
        val paidOrUnknown: Int
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("catalog_models", modelsSeen)
            .put("nsfw_candidates", candidates)
            .put("free_variants", freeVariants)
            .put("zero_priced_candidates", zeroPriced)
            .put("eligible_free_routes", eligible)
            .put("paid_or_unknown", paidOrUnknown)
    }

    fun isCandidate(id: String, name: String = "", description: String = ""): Boolean =
        adultAndRoleplay.containsMatchIn("$id $name $description")

    fun parse(raw: String): JSONArray = parseDetailed(raw).models

    fun parseDetailed(raw: String): CatalogResult {
        val data = JSONObject(raw).optJSONArray("data") ?: JSONArray()
        val candidates = mutableListOf<JSONObject>()
        var candidateCount = 0
        var freeVariantCount = 0
        var zeroPricedCount = 0
        var excludedCount = 0

        for (index in 0 until data.length()) {
            val model = data.optJSONObject(index) ?: continue
            val id = model.optString("id").trim()
            if (id.isBlank()) continue
            val name = model.optString("name").ifBlank { id }
            val description = model.optString("description")
            if (!isCandidate(id, name, description)) continue
            candidateCount++

            val freeVariant = id.endsWith(":free", ignoreCase = true)
            if (freeVariant) freeVariantCount++
            val pricing = model.optJSONObject("pricing")
            val input = pricing?.let { price(it, "prompt") }
            val output = pricing?.let { price(it, "completion") }
            val zeroPriced = input == 0.0 && output == 0.0
            if (zeroPriced) zeroPricedCount++

            // :free is important: OpenRouter says other $0 models may still
            // require a funded account. Unknown prices are never assumed free.
            if (!freeVariant || !zeroPriced) {
                excludedCount++
                continue
            }

            val context = model.optInt("context_length", 0).coerceAtLeast(0)
            val familyBoost = when {
                Regex("(?i)(cydonia|rocinante|noromaid|lumimaid|mythomax|unslopnemo|stheno|magnum)").containsMatchIn(id) -> 100.0
                Regex("(?i)(abliterat|uncensored|dark[-_ ]?idol|tiefighter|eva[-_ ]?llama)").containsMatchIn(id) -> 90.0
                else -> 70.0
            }
            val score = familyBoost + (context.coerceAtMost(200000) / 10000.0)
            candidates.add(
                JSONObject()
                    .put("model", id)
                    .put("name", name)
                    .put("description", description.take(500))
                    .put("free", true)
                    .put("free_variant", true)
                    .put("price_input", input)
                    .put("price_output", output)
                    .put("context_length", context)
                    .put("score", score)
            )
        }

        val sorted = candidates.sortedWith(
            compareByDescending<JSONObject> { it.optDouble("score", 0.0) }
                .thenByDescending { it.optInt("context_length", 0) }
                .thenBy { it.optString("model") }
        )
        val output = JSONArray().also { array -> sorted.take(30).forEach(array::put) }
        return CatalogResult(
            output, data.length(), candidateCount, freeVariantCount,
            zeroPricedCount, candidates.size, excludedCount
        )
    }

    private fun price(pricing: JSONObject, key: String): Double? {
        val raw = pricing.opt(key)
        return when (raw) {
            is Number -> raw.toDouble().takeIf { it.isFinite() && it >= 0.0 }
            is String -> raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            else -> null
        }
    }
}
