package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object RouterAPIMigration {
    fun ensure(root: File) {
        val cfg = runCatching { JSONObject(SecretStore.readRuntimeConfig(root)) }.getOrElse { return }
        if (cfg.optBoolean("routerapi_migration_v2")) return
        applyMigration(cfg)
        SecretStore.saveRuntimeConfig(root, cfg.toString(2))
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
        cfg.put("route_tasks", JSONObject()
            .put("chat", JSONArray().put(meroId).put("routerapi-glm").put("routerapi-cydonia"))
            .put("warmth", JSONArray().put(meroId).put("routerapi-cydonia").put("routerapi-unslop"))
            .put("thought", JSONArray().put(meroId).put("routerapi-cydonia"))
            .put("code", JSONArray().put("routerapi-glm").put("routerapi-deepseek").put(meroId))
            .put("vision", JSONArray().put(meroId).put("routerapi-glm"))
            .put("long", JSONArray().put("routerapi-unslop").put("routerapi-cydonia")))
        cfg.put("routerapi_migration_v2", true)
    }
}
