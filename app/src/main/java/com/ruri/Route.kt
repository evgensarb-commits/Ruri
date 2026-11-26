package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Provider selection. Three mutually exclusive options:
 *
 * - **free** — Gemma 4 26B MeroMero on Routeway's free tier (daily token limit).
 * - **paid** — Gemma 4 31B SDFT Heretic RP on Routeway, billed per token.
 * - **routerai** — a model of the Commander's choice at routerai.ru, a separate provider with
 *   its own base URL and its own API key.
 *
 * Each option has its own id, so [SecretStore] keeps their keys in separate slots: switching
 * providers never overwrites or leaks another provider's credential.
 *
 * [ensure] rewrites only the provider section of `core/config.json`; it never touches memory.
 */
object Route {
    // ---------------------------------------------------------------- providers

    const val ID_ROUTEWAY = "routeway"
    const val ID_ROUTERAI = "routerai"

    const val ROUTEWAY_BASE_URL = "https://api.routeway.ai/v1"
    const val ROUTERAI_BASE_URL = "https://routerai.ru/api/v1"

    /** Free-tier MeroMero (A4B, ~4B active parameters). */
    const val MODEL_FREE = "gemma-4-26b-a4b-it-meromero:free"

    /** Paid variant chosen by the Commander. Overridable via [paidModelId]. */
    const val MODEL_PAID_DEFAULT = "gemma-4-31b-sdft-heretic-rp"

    /**
     * Default model on routerai.ru: a Gemma-family model of comparable size, so the character
     * voice survives the move. Overridable from settings — the catalogue changes.
     */
    const val MODEL_ROUTERAI_DEFAULT = "google/gemma-3-27b-it"

    /** Ids an earlier build wrote by guessing. Never kept: they point at nothing real. */
    private val MODEL_PAID_LEGACY_IDS = listOf(
        "gemma-4-31b-it-meromero",
        "gemma-4-31b-meromero-v2"
    )

    const val VARIANT_FREE = "free"
    const val VARIANT_PAID = "paid"
    const val VARIANT_ROUTERAI = "routerai"

    /** Routeway's MeroMero is reachable from this region only through a VPN tunnel. */
    const val ROUTEWAY_REQUIRES_VPN = true

    /** routerai.ru is a Russian provider: no tunnel needed. */
    const val ROUTERAI_REQUIRES_VPN = false

    private const val CONFIG_PATH = "core/config.json"
    private const val VARIANT_KEY = "model_variant"
    private const val PAID_MODEL_KEY = "paid_model_id"
    private const val ROUTERAI_MODEL_KEY = "routerai_model_id"

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

    // ---------------------------------------------------------------- variant

    fun variant(root: File): String = when (publicConfig(root).optString(VARIANT_KEY, VARIANT_FREE)) {
        VARIANT_PAID -> VARIANT_PAID
        VARIANT_ROUTERAI -> VARIANT_ROUTERAI
        else -> VARIANT_FREE
    }

    fun isPaid(root: File): Boolean = variant(root) == VARIANT_PAID

    fun isRouterAI(root: File): Boolean = variant(root) == VARIANT_ROUTERAI

    /** Which provider the active variant talks to. */
    fun providerId(root: File): String = if (isRouterAI(root)) ID_ROUTERAI else ID_ROUTEWAY

    fun baseUrl(root: File): String = if (isRouterAI(root)) ROUTERAI_BASE_URL else ROUTEWAY_BASE_URL

    fun requiresVpn(root: File): Boolean = !isRouterAI(root) && ROUTEWAY_REQUIRES_VPN

    /** Provider model id for the active variant. */
    fun model(root: File): String = when (variant(root)) {
        VARIANT_PAID -> paidModelId(root)
        VARIANT_ROUTERAI -> routerAiModelId(root)
        else -> MODEL_FREE
    }

    fun modelLabel(root: File): String = when (variant(root)) {
        VARIANT_PAID -> MODEL_PAID_LABEL
        VARIANT_ROUTERAI -> "routerai · ${routerAiModelId(root)}"
        else -> MODEL_FREE_LABEL
    }

    private const val MODEL_FREE_LABEL = "Gemma 4 26B MeroMero (free)"
    private const val MODEL_PAID_LABEL = "Gemma 4 31B SDFT Heretic RP (paid)"

    /**
     * Switches the active provider.
     *
     * The new model entry is written with a **blank key**: each provider keeps its own
     * credential in [SecretStore] under its own id, and carrying the current key across would
     * save Routeway's token into routerai's slot.
     */
    fun setVariant(root: File, variant: String) {
        val target = when (variant) {
            VARIANT_PAID -> VARIANT_PAID
            VARIANT_ROUTERAI -> VARIANT_ROUTERAI
            else -> VARIANT_FREE
        }
        val modelId = when (target) {
            VARIANT_PAID -> paidModelId(root)
            VARIANT_ROUTERAI -> routerAiModelId(root)
            else -> MODEL_FREE
        }
        val config = readConfig(root).put(VARIANT_KEY, target)
        retiredKeys.forEach { config.remove(it) }
        config.put("models", JSONArray().put(entryFor(target, "", modelId))).put("current", 0)
        persist(root, config)
    }

    // ---------------------------------------------------------------- model ids

    fun paidModelId(root: File): String {
        val stored = publicConfig(root).optString(PAID_MODEL_KEY).trim()
        // A config written by an earlier build may hold a guessed id; never keep it.
        if (stored.isBlank() || stored in MODEL_PAID_LEGACY_IDS) return MODEL_PAID_DEFAULT
        return stored
    }

    fun routerAiModelId(root: File): String =
        publicConfig(root).optString(ROUTERAI_MODEL_KEY).trim().ifBlank { MODEL_ROUTERAI_DEFAULT }

    fun setPaidModelId(root: File, id: String) {
        val trimmed = id.trim()
        val config = readConfig(root).put(PAID_MODEL_KEY, trimmed)
        if (isPaid(root)) {
            config.put("models", JSONArray().put(entryFor(VARIANT_PAID, "", trimmed)))
        }
        persist(root, config)
    }

    fun setRouterAiModelId(root: File, id: String) {
        val trimmed = id.trim()
        val config = readConfig(root).put(ROUTERAI_MODEL_KEY, trimmed)
        if (isRouterAI(root)) {
            config.put("models", JSONArray().put(entryFor(VARIANT_ROUTERAI, "", trimmed)))
        }
        persist(root, config)
    }

    // ---------------------------------------------------------------- entries

    /** One provider entry as stored in `core/config.json`. */
    private fun entryFor(variant: String, apiKey: String, modelId: String): JSONObject {
        val isRouter = variant == VARIANT_ROUTERAI
        return JSONObject()
            .put("id", if (isRouter) ID_ROUTERAI else ID_ROUTEWAY)
            .put("name", if (isRouter) "routerai.ru" else "Routeway")
            .put("base_url", if (isRouter) ROUTERAI_BASE_URL else ROUTEWAY_BASE_URL)
            .put("model", modelId)
            .put("api_key", apiKey)
            .put("needs_vpn", !isRouter && ROUTEWAY_REQUIRES_VPN)
            .put("context_length", 131072)
    }

    /** Canonical entry for the active variant with the given key. */
    fun canonical(apiKey: String = "", modelId: String = MODEL_FREE): JSONObject =
        entryFor(variantOf(modelId), apiKey, modelId)

    /** Guess which provider a model id belongs to, so `canonical` stays usable in tests. */
    private fun variantOf(modelId: String): String = when {
        modelId == MODEL_FREE || modelId == MODEL_PAID_DEFAULT -> VARIANT_FREE
        modelId.contains("gemma-4", ignoreCase = true) -> VARIANT_FREE
        else -> VARIANT_ROUTERAI
    }

    // ---------------------------------------------------------------- key

    /** Key of the active provider, injected by SecretStore from the encrypted store. */
    fun apiKey(root: File): String {
        val wanted = providerId(root)
        val models = readConfig(root).optJSONArray("models") ?: return ""
        // Prefer the entry whose id matches the active provider, so a stale foreign entry
        // cannot hand us the wrong credential.
        for (index in 0 until models.length()) {
            val model = models.optJSONObject(index) ?: continue
            if (model.optString("id") != wanted) continue
            val key = model.optString("api_key").orEmpty().trim()
            if (key.isNotBlank()) return key
        }
        for (index in 0 until models.length()) {
            val key = models.optJSONObject(index)?.optString("api_key").orEmpty().trim()
            if (key.isNotBlank()) return key
        }
        return ""
    }

    fun hasKey(root: File): Boolean = apiKey(root).isNotBlank()

    /** Saves the key of the active provider only. */
    fun setApiKey(root: File, key: String) {
        val config = readConfig(root)
        val variant = variant(root)
        config.put("models", JSONArray().put(entryFor(variant, key.trim(), model(root))))
            .put("current", 0)
        retiredKeys.forEach { config.remove(it) }
        persist(root, config)
    }

    /** Saves a provider key in its own slot, without switching the active provider. */
    fun setProviderKey(root: File, providerId: String, key: String) {
        val secretId = if (providerId == ID_ROUTERAI) ID_ROUTERAI else ID_ROUTEWAY
        SecretStore.setModelKey(root, secretId, key)
        // If that provider is active right now, the visible entry must reflect the change.
        if (secretId == providerId(root)) {
            val config = readConfig(root)
            config.put("models", JSONArray().put(entryFor(variant(root), key.trim(), model(root))))
                .put("current", 0)
            persist(root, config)
        }
    }

    fun hasProviderKey(root: File, providerId: String): Boolean =
        SecretStore.hasModelKey(root, if (providerId == ID_ROUTERAI) ID_ROUTERAI else ID_ROUTEWAY)

    // ---------------------------------------------------------------- ensure

    /**
     * Makes the provider section canonical. Idempotent and non-destructive: memory files are
     * never read or written here. Returns true when the config had to be rewritten.
     */
    fun ensure(root: File): Boolean {
        val config = readConfig(root)
        val models = config.optJSONArray("models")
        val only = models?.optJSONObject(0)
        val expectedId = providerId(root)
        val expectedBase = baseUrl(root)
        val expectedModel = model(root)
        val canonicalShape = models != null && models.length() == 1 &&
            only?.optString("id") == expectedId &&
            only?.optString("base_url") == expectedBase &&
            only?.optString("model") == expectedModel
        val hasRetired = retiredKeys.any { config.has(it) } ||
            only?.has("vision") == true || only?.has("nsfw") == true || only?.has("free_tier") == true
        if (canonicalShape && !hasRetired) return false

        // Preserve the active provider's key; other providers keep theirs under their own id.
        val key = apiKey(root)
        config.put("models", JSONArray().put(entryFor(variant(root), key, expectedModel)))
            .put("current", 0)
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
