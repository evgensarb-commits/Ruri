package com.ruri

import org.json.JSONArray
import org.json.JSONObject

/** Strict free-only Hugging Face Inference Providers catalog. Unknown prices/status are excluded. */
internal object HFModelCatalog {
    private val roleplay = Regex("(?i)(cydonia|rocinante|unslopnemo|magnum|roleplay|mythomax|stheno|noromaid|eva[-_ ]?llama|tiefighter|lumimaid|rpmax|anubis|dark[-_ ]?idol|fimbulvetr|sao10k|midnight[-_ ]?miqu|lewd|abliterat)")
    private val forbiddenLegacy = Regex("(?i)dolphin")

    fun parse(raw: String): JSONArray {
        val data = JSONObject(raw).optJSONArray("data") ?: JSONArray()
        val candidates = mutableListOf<JSONObject>()
        for (i in 0 until data.length()) {
            val model = data.optJSONObject(i) ?: continue
            val modelId = model.optString("id").trim()
            if (modelId.isBlank() || !roleplay.containsMatchIn(modelId) || forbiddenLegacy.containsMatchIn(modelId)) continue
            val declaredTask = model.optString("task").ifBlank { model.optString("pipeline_tag") }
            if (declaredTask.isNotBlank() &&
                !declaredTask.contains("text-generation", true) &&
                !declaredTask.contains("chat-completion", true) &&
                !declaredTask.contains("conversational", true)) continue
            val providers = model.optJSONArray("providers") ?: model.optJSONArray("inference_providers") ?: continue
            for (p in 0 until providers.length()) {
                val provider = providers.optJSONObject(p) ?: continue
                if (!provider.optString("status").equals("live", true)) continue
                if (provider.has("is_free") && !provider.optBoolean("is_free")) continue
                val pricing = provider.optJSONObject("pricing") ?: continue
                val input = price(pricing, "input", "prompt")
                val output = price(pricing, "output", "completion")
                if (input == null || output == null || input != 0.0 || output != 0.0) continue
                val providerId = provider.optString("provider").ifBlank { provider.optString("name") }
                    .trim().lowercase()
                if (providerId.isBlank() || !providerId.matches(Regex("[a-z0-9_-]{1,80}"))) continue
                val context = provider.optInt("context_length", model.optInt("context_length", 0)).coerceAtLeast(0)
                val throughput = number(provider, "throughput")
                val latency = number(provider, "first_token_latency_ms") ?: number(provider, "latency")
                val roleplayBoost = when {
                    modelId.contains("cydonia", true) || modelId.contains("rocinante", true) -> 100.0
                    modelId.contains("unslopnemo", true) || modelId.contains("magnum", true) -> 90.0
                    else -> 70.0
                }
                val score = roleplayBoost + (throughput ?: 0.0).coerceAtMost(200.0) * 0.2 +
                    (context.coerceAtMost(200000) / 10000.0) - (latency ?: 1000.0).coerceAtMost(1000.0) * 0.02
                candidates.add(JSONObject()
                    .put("model", modelId)
                    .put("provider", providerId)
                    .put("name", model.optString("name").ifBlank { modelId })
                    .put("free", true)
                    .put("price_input", input)
                    .put("price_output", output)
                    .put("context_length", context)
                    .put("throughput", throughput ?: JSONObject.NULL)
                    .put("latency", latency ?: JSONObject.NULL)
                    .put("score", score))
            }
        }
        val sorted = candidates.sortedWith(
            compareByDescending<JSONObject> { it.optDouble("score", 0.0) }
                .thenByDescending { it.optInt("context_length", 0) }
                .thenBy { it.optString("model") }
                .thenBy { it.optString("provider") }
        )
        return JSONArray().also { out -> sorted.take(30).forEach(out::put) }
    }

    private fun price(pricing: JSONObject, primary: String, alternate: String): Double? {
        val raw = if (pricing.has(primary)) pricing.opt(primary) else pricing.opt(alternate)
        return when (raw) {
            is Number -> raw.toDouble().takeIf { it.isFinite() && it >= 0.0 }
            is String -> raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            else -> null
        }
    }

    private fun number(obj: JSONObject, key: String): Double? {
        val raw = obj.opt(key)
        return when (raw) {
            is Number -> raw.toDouble().takeIf { it.isFinite() && it >= 0.0 }
            is String -> raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            else -> null
        }
    }
}
