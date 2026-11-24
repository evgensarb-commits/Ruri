package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The single supported provider: Routeway's free Gemma MeroMero.
 *
 * There is deliberately no model list, no routing between routes and no fallback chain.
 * [ensure] rewrites only the provider section of `core/config.json`; it never touches
 * memory files, and it preserves the stored API key.
 */
object Route {
    const val ID = "routeway"
    const val DISPLAY_NAME = "Gemma MeroMero"
    const val BASE_URL = "https://api.routeway.ai/v1"
    const val MODEL = "gemma-4-26b-a4b-it-meromero:free"

    /** MeroMero is reachable from this region only through a VPN tunnel. */
    const val REQUIRES_VPN = true

    private const val CONFIG_PATH = "core/config.json"

    /** Config keys that belonged to removed features (multi-route, vision, shelf, search). */
    private val retiredKeys = listOf(
        "route_tasks", "nsfw_override", "free_only", "exclusive_route",
        "two_route_migration_v1", "manual_route_order_v1", "routes_v3_no_minimax",
        "routerapi_migration_v2", "routerapi_key", "routerapi_base",
        "selected_route_id", "route_mode", "search_key", "serper_key", "searxng_url"
    )

    fun canonical(apiKey: String = ""): JSONObject = JSONObject()
        .put("id", ID)
        .put("name", DISPLAY_NAME)
        .put("base_url", BASE_URL)
        .put("model", MODEL)
        .put("api_key", apiKey)
        .put("needs_vpn", REQUIRES_VPN)
        .put("context_length", 131072)

    private fun readConfig(root: File): JSONObject {
        val fromStore = runCatching { JSONObject(SecretStore.readRuntimeConfig(root)) }.getOrNull()
        if (fromStore != null && fromStore.optJSONArray("models") != null) return fromStore
        // Fallback keeps the route usable even when the encrypted store cannot be opened.
        return runCatching { JSONObject(FileManager.read(root, CONFIG_PATH)) }.getOrElse { JSONObject() }
    }

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
            .put("models", JSONArray().put(canonical(key.trim())))
            .put("current", 0)
        retiredKeys.forEach { config.remove(it) }
        val saved = runCatching { SecretStore.saveRuntimeConfig(root, config.toString(2)) }
        if (saved.isFailure) {
            // The encrypted store is unavailable: keep a valid route structure on disk with a
            // blank key (never plaintext) so the app reports "no key" instead of a broken route.
            val publicOnly = JSONObject(config.toString())
            val models = publicOnly.optJSONArray("models") ?: JSONArray()
            for (index in 0 until models.length()) models.optJSONObject(index)?.put("api_key", "")
            runCatching { FileManager.write(root, CONFIG_PATH, publicOnly.toString(2)) }
        }
    }

    /**
     * Makes the provider section canonical. Idempotent and non-destructive: memory files
     * are never read or written here. Returns true when the config had to be rewritten.
     */
    fun ensure(root: File): Boolean {
        val config = readConfig(root)
        val models = config.optJSONArray("models")
        val only = models?.optJSONObject(0)
        val canonicalShape = models != null && models.length() == 1 &&
            only?.optString("id") == ID &&
            only?.optString("base_url") == BASE_URL &&
            only?.optString("model") == MODEL
        val hasRetired = retiredKeys.any { config.has(it) } ||
            only?.has("vision") == true || only?.has("nsfw") == true || only?.has("free_tier") == true
        if (canonicalShape && !hasRetired) return false

        var key = ""
        if (models != null) {
            for (index in 0 until models.length()) {
                val candidate = models.optJSONObject(index)?.optString("api_key").orEmpty().trim()
                if (candidate.isNotBlank()) { key = candidate; break }
            }
        }
        config.put("models", JSONArray().put(canonical(key))).put("current", 0)
        retiredKeys.forEach { config.remove(it) }
        val saved = runCatching { SecretStore.saveRuntimeConfig(root, config.toString(2)) }
        if (saved.isFailure) {
            // The encrypted store cannot be opened: keep a valid route structure on disk with
            // a blank key (never plaintext) so the app reports "no key" instead of crashing.
            val publicOnly = JSONObject(config.toString())
            val sanitized = publicOnly.optJSONArray("models") ?: JSONArray()
            for (index in 0 until sanitized.length()) sanitized.optJSONObject(index)?.put("api_key", "")
            runCatching { FileManager.write(root, CONFIG_PATH, publicOnly.toString(2)) }
        }
        return true
    }
}
