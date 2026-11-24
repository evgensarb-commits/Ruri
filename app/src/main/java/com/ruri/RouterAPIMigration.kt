package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object RouterAPIMigration {
    fun ensure(root: File) {
        val cfg = runCatching { JSONObject(SecretStore.readRuntimeConfig(root)) }.getOrElse { return }
        // The one-shot flag alone is not enough: routes can be lost on the device
        // (partial save, manual cleanup, failed write). If no routerapi-* model
        // exists, re-run the migration even when the flag is already set —
        // applyMigration is idempotent for a healthy config.
        if (!needsRepair(cfg) && cfg.optBoolean("routerapi_migration_v2")) return
        applyMigration(cfg)
        SecretStore.saveRuntimeConfig(root, cfg.toString(2))
    }

    /** True when the config has no routerapi routes at all (or no models). */
    internal fun needsRepair(cfg: JSONObject): Boolean {
        val models = cfg.optJSONArray("models") ?: return true
        for (i in 0 until models.length()) {
            if (models.optJSONObject(i)?.optString("id").orEmpty().startsWith("routerapi-")) return false
        }
        return true
    }

    internal fun applyMigration(cfg: JSONObject) {
        val models = cfg.optJSONArray("models") ?: JSONArray()
        val kept = JSONArray(); var meroId = "routeway"
        for (i in 0 until models.length()) {
            val m = models.optJSONObject(i) ?: continue
            if (m.optString("model").contains("meromero", true)) {
                meroId = m.optString("id").ifBlank { "routeway" }
                m.put("id", meroId); m.put("priority", 1); m.put("nsfw", true); m.put("vision", true)
                kept.put(m)
            }
        }
        val raKey = cfg.optString("routerapi_key", "sk-QXEjR-DeMscmrDbKE5PH8YiuIPOsZxq0")
        val raBase = cfg.optString("routerapi_base", "https://routerai.ru/api/v1")
        fun ra(id: String, name: String, model: String, nsfw: Boolean, vision: Boolean, ctx: Int) = JSONObject()
            .put("id", id).put("name", name).put("base_url", raBase).put("model", model).put("api_key", raKey)
            .put("free_tier", true).put("enabled", raKey.isNotBlank()).put("nsfw", nsfw).put("vision", vision)
            .put("context_length", ctx).put("rpd", 0).put("tokens_per_day", 0).put("reset_hour_utc", 0)
            .put("needs_vpn", false).put("ru_reachable", true)
        val wanted = listOf(
            ra("routerapi-glm", "GLM 5.3 Flash (RF)", "z-ai/glm-5.3-flash", false, true, 1000000),
            ra("routerapi-cydonia", "Cydonia 24B V4.1", "thedrummer/cydonia-24b-v4.1", true, false, 131000),
            ra("routerapi-unslop", "UnslopNemo 12B", "thedrummer/unslopnemo-12b", true, false, 1000000),
            ra("routerapi-deepseek", "DeepSeek V4 Pro 0813", "deepseek/deepseek-v4-pro-0813", false, false, 1000000))
        for (w in wanted) {
            var has = false
            for (i in 0 until kept.length()) if (kept.optJSONObject(i).optString("id") == w.optString("id")) has = true
            if (!has) kept.put(w)
        }
        cfg.put("models", kept) // minimax/dolphin :free/arliai/hf удаляются
        // Preserve a customized task order; write the defaults only when the
        // config carries no task list (fresh install or lost during a broken save).
        if (!cfg.has("route_tasks")) {
            cfg.put("route_tasks", JSONObject()
                .put("chat", JSONArray().put(meroId).put("routerapi-glm").put("routerapi-cydonia"))
                .put("warmth", JSONArray().put(meroId).put("routerapi-cydonia").put("routerapi-unslop"))
                .put("thought", JSONArray().put(meroId).put("routerapi-cydonia"))
                .put("code", JSONArray().put("routerapi-glm").put("routerapi-deepseek").put(meroId))
                .put("vision", JSONArray().put(meroId).put("routerapi-glm"))
                .put("long", JSONArray().put("routerapi-unslop").put("routerapi-cydonia")))
        }
        cfg.put("routerapi_migration_v2", true)
        // A selection pointing at a route that no longer exists leaves MANUAL mode
        // with an empty order ("Нет настроенного маршрута"). Fall back to the
        // primary free route in AUTO so the app stays talkative after repair.
        val keptModels = cfg.optJSONArray("models") ?: JSONArray()
        val selectedId = cfg.optString("selected_route_id")
        val selectedExists = (0 until keptModels.length()).any {
            keptModels.optJSONObject(it)?.optString("id") == selectedId
        }
        if (!selectedExists) {
            var meroIndex = 0
            for (i in 0 until keptModels.length()) {
                if (keptModels.optJSONObject(i)?.optString("id") == meroId) { meroIndex = i; break }
            }
            cfg.put("selected_route_id", meroId)
                .put("route_mode", "AUTO")
                .put("current", meroIndex)
        }
    }
}
