package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The single supported provider: Routeway, with two variants of the same model.
 *
 * - **free** — Gemma 4 26B MeroMero on the free tier (daily token limit).
 * - **paid** — the larger MeroMero, billed per token, no daily cap.
 *
 * The provider id of a paid variant cannot be discovered without the catalogue, so the paid
 * id is stored in config and can be corrected from the UI without a rebuild.
 *
 * [ensure] rewrites only the provider section of `core/config.json`; it never touches memory.
 */
object Route {
    const val ID = "routeway"
    const val DISPLAY_NAME = "Gemma MeroMero"
    const val BASE_URL = "https://api.routeway.ai/v1"

    /** Free-tier MeroMero (A4B, ~4B active parameters). */
    const val MODEL_FREE = "gemma-4-26b-a4b-it-meromero:free"

    /** Paid MeroMero 31B. Overridable via [paidModelId] when the catalogue differs. */
    const val MODEL_PAID_DEFAULT = "gemma-4-31b-it-meromero"

    const val VARIANT_FREE = "free"
    const val VARIANT_PAID = "paid"

    /** MeroMero is reachable from this region only through a VPN tunnel. */
    const val REQUIRES_VPN = true

    private const val CONFIG_PATH = "core/config.json"
    private const val VARIANT_KEY = "model_variant"
    private const val PAID_MODEL_KEY = "paid_model_id"

    /** Retired feature keys removed on every [ensure]. */
    private val retiredKeys = listOf(
        "route_tasks", "nsfw_override", "free_only", "exclusive_route",
        "two_route_migration_v1", "manual_route_order_v1", "routes_v3_no_minimax",
        "routerapi_migration_v2", "routerapi_key", "routerapi_base",
        "selected_route_id", "route_mode", "search_key", "serper_key", "searxng_url"
    )

    private fun publicConfig(root: File): JSONObject = runCatching {
        JSONObject(FileManager.read(root, CONFIG_PATH))
    }.getOrElse { JSONObject() }

    /** Config with provider keys injected by [SecretStore] where available. */
    private fun readConfig(root: File): JSONObject {
        val fromStore = runCatching { JSONObject(SecretStore.readRuntimeConfig(root)) }.getOrNull()
        if (fromStore != null && fromStore.optJSONArray("models") != null) return fromStore
        return publicConfig(root)
    }

    // ------------------------------------------------------------------ variant

    fun variant(root: File): String {
        val value = publicConfig(root).optString(VARIANT_KEY, VARIANT_FREE)
        return if (value == VARIANT_PAID) VARIANT_PAID else VARIANT_FREE
    }

    fun isPaid(root: File): Boolean = variant(root) == VARIANT_PAID

    /** Stores the chosen variant and rewrites the provider section to match. */
    fun setVariant(root: File, variant: String) {
        val normalized = if (variant == VARIANT_PAID) VARIANT_PAID else VARIANT_FREE
        // Resolve the target id before writing: canonical() must not re-read the old variant.
        val targetModel = if (normalized == VARIANT_PAID) paidModelId(root) else MODEL_FREE
        val config = readConfig(root).put(VARIANT_KEY, normalized)
        retiredKeys.forEach { config.remove(it) }
        config.put("models", JSONArray().put(canonical(apiKey(root), targetModel)))
        persist(root, config)
    }

    fun paidModelId(root: File): String =
        publicConfig(root).optString(PAID_MODEL_KEY, MODEL_PAID_DEFAULT).ifBlank { MODEL_PAID_DEFAULT }

    fun setPaidModelId(root: File, id: String) {
        val trimmed = id.trim()
        val targetModel = if (isPaid(root)) trimmed else MODEL_FREE
        val config = readConfig(root).put(PAID_MODEL_KEY, trimmed)
        config.put("models", JSONArray().put(canonical(apiKey(root), targetModel)))
        persist(root, config)
    }

    /** Provider model id for the active variant. */
    fun model(root: File): String = if (isPaid(root)) paidModelId(root) else MODEL_FREE

    /** Human label for the diagnostics screen and the UI. */
    fun modelLabel(root: File): String =
        if (isPaid(root)) "$MODEL_PAID_LABEL" else "$MODEL_FREE_LABEL"

    private const val MODEL_FREE_LABEL = "Gemma 4 26B MeroMero (free)"
    private const val MODEL_PAID_LABEL = "Gemma 4 31B MeroMero (paid)"

    fun canonical(apiKey: String = "", modelId: String = MODEL_FREE): JSONObject {
        return JSONObject()
            .put("id", ID)
            .put("name", DISPLAY_NAME)
            .put("base_url", BASE_URL)
            .put("model", modelId)
            .put("api_key", apiKey)
            .put("needs_vpn", REQUIRES_VPN)
            .put("context_length", 131072)
    }

    // ------------------------------------------------------------------ key

    /** The provider key, injected by SecretStore from the encrypted store. */
    fun apiKey(root: File): String {
        val models = readConfig(root).optJSONArray("models") ?: return ""
        for (index in 0 until models.length()) {
            val key = models.optJSONObject(index)?.optString("api_key").orEmpty().trim()
            if (key.isNotBlank()) return key
        }
        return ""
    }

    fun hasKey(root: File): Boolean = apiKey(root).isNotBlank()

    fun setApiKey(root: File, key: String) {
        val config = readConfig(root)
        config.put("models", JSONArray().put(canonical(key.trim(), model(root)))).put("current", 0)
        retiredKeys.forEach { config.remove(it) }
        persist(root, config)
    }

    // ------------------------------------------------------------------ ensure

    /**
     * Makes the provider section canonical. Idempotent and non-destructive: memory files are
     * never read or written here. Returns true when the config had to be rewritten.
     */
    fun ensure(root: File): Boolean {
        val config = readConfig(root)
        val models = config.optJSONArray("models")
        val only = models?.optJSONObject(0)
        val expectedModel = model(root)
        val canonicalShape = models != null && models.length() == 1 &&
            only?.optString("id") == ID &&
            only?.optString("base_url") == BASE_URL &&
            only?.optString("model") == expectedModel
        val hasRetired = retiredKeys.any { config.has(it) } ||
            only?.has("vision") == true || only?.has("nsfw") == true || only?.has("free_tier") == true
        if (canonicalShape && !hasRetired) return false

        val key = apiKey(root)
        config.put("models", JSONArray().put(canonical(key, expectedModel))).put("current", 0)
        retiredKeys.forEach { config.remove(it) }
        persist(root, config)
        return true
    }

    /**
     * Saves through [SecretStore] so the key never lands on disk in clear text. If the
     * encrypted store cannot be opened, a canonical config with a blank key is written
     * instead, so the app reports "no key" rather than crashing.
     */
    private fun persist(root: File, config: JSONObject) {
        val saved = runCatching { SecretStore.saveRuntimeConfig(root, config.toString(2)) }
        if (saved.isFailure) {
            val publicOnly = JSONObject(config.toString())
            val sanitized = publicOnly.optJSONArray("models") ?: JSONArray()
            for (index in 0 until sanitized.length()) sanitized.optJSONObject(index)?.put("api_key", "")
            runCatching { FileManager.write(root, CONFIG_PATH, publicOnly.toString(2)) }
        }
    }
}
