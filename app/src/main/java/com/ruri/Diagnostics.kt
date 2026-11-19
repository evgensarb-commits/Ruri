package com.ruri

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Read-only, privacy-safe snapshot for the in-app diagnostics screen.
 * Provider keys are read only to determine whether a key exists and are never returned.
 */
object Diagnostics {
    fun run(ctx: Context): String {
        val root = ctx.filesDir
        NetState.refresh(ctx.applicationContext)
        val config = runCatching { SecretStore.readRuntimeConfig(root) }
            .getOrDefault("{}")
        return collect(root, config, NetState.online, NetState.vpn)
    }

    internal fun collect(
        root: File,
        runtimeConfig: String,
        online: Boolean,
        vpn: Boolean,
        now: Long = System.currentTimeMillis()
    ): String {
        val result = JSONObject()
        result.put("generated_at", now)
        result.put("network", JSONObject()
            .put("online", online)
            .put("vpn_active", vpn))
        result.put("offline_pending", OfflineQueue.hasPending(root))

        val config = runCatching { JSONObject(runtimeConfig) }.getOrDefault(JSONObject())
        val models = config.optJSONArray("models") ?: JSONArray()
        val routeMetadata = RouteMeta.load(root)
        val routes = JSONObject()
        val utcDay = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(now))

        for (index in 0 until models.length()) {
            val model = models.optJSONObject(index) ?: continue
            val id = model.optString("id")
                .ifBlank { model.optString("name").ifBlank { "route_$index" } }
            val meta = routeMetadata.optJSONObject(id) ?: JSONObject()
            val caps = meta.optJSONObject("caps") ?: JSONObject()
            val modelNsfwStatus = model.optString("nsfw_probe_status", "not_tested")
            val nsfwCapable = when {
                model.has("nsfw_capable") -> model.optBoolean("nsfw_capable")
                caps.has("nsfw") -> caps.optBoolean("nsfw")
                else -> false
            }
            val dayCount = if (meta.optString("day_key") == utcDay) meta.optInt("day_count", 0) else 0
            val route = JSONObject()
                .put("name", model.optString("name", id))
                .put("model", model.optString("model"))
                .put("enabled", model.optBoolean("enabled", true))
                .put("free_tier", model.optBoolean("free_tier", false))
                .put("has_key", model.optString("api_key").isNotBlank())
                .put("allow_anonymous", model.optBoolean("allow_anonymous", false))
                .put("api_key_configured", model.optString("api_key").isNotBlank() ||
                    model.optBoolean("allow_anonymous", false))
                .put("reachable", meta.optBoolean("reachable", true))
                .put("needs_vpn", meta.optBoolean("needs_vpn", model.optBoolean("needs_vpn", false)))
                .put("nsfw_capable", nsfwCapable)
                .put("nsfw_probe_status", modelNsfwStatus)
                .put("nsfw_tested_at", model.optLong("nsfw_tested_at", 0L))
                .put("in_cooldown", meta.optLong("cooldown_until", 0L) > now)
                .put("cooldown_until", meta.optLong("cooldown_until", 0L))
                .put("requests_today", dayCount)
                .put("requests_per_day", model.optInt("rpd", 200))
                .put("quota_remaining", (model.optInt("rpd", 200) - dayCount).coerceAtLeast(0))
                .put("last_reachability_at", meta.optLong("last_reachability_at", 0L))
                .put("chat_score", RouteSelector.score(meta, Task.CHAT))
                .put("last_attempt", meta.optLong("last_attempt", 0L))
                .put("last_success", meta.optLong("last_ok", 0L))
            routes.put(id, route)
        }
        result.put("routes", routes)
        result.put("current_route", config.optInt("current", -1))
        result.put("exclusive_route", config.optBoolean("exclusive_route", false))
        val latestNetwork = runCatching { JSONObject(NetworkDiagnostics.latest(root)) }.getOrDefault(JSONObject())
        result.put("last_error", if (latestNetwork.optBoolean("available", false)) {
            JSONObject().put("provider", latestNetwork.optString("provider"))
                .put("message", latestNetwork.optString("failure_message"))
                .put("stage", latestNetwork.optString("stage"))
                .put("http_status", latestNetwork.opt("http_status") ?: JSONObject.NULL)
                .put("error_type", latestNetwork.optString("error_type"))
                .put("result", latestNetwork.optString("result"))
        } else JSONObject())

        val rollingDir = File(root, "backup/rolling")
        val rollingFiles = rollingDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
            ?: emptyList()
        val checkpoint = File(root, "backup/checkpoints/L4_state.json")
        val automaticStatus = runCatching { LocalBackupManager.status(root) }.getOrDefault(JSONObject())
        val automatic = automaticStatus.optJSONArray("backups") ?: JSONArray()
        result.put("backups", JSONObject()
            .put("rolling_count", rollingFiles.size)
            .put("rolling_latest_at", rollingFiles.maxOfOrNull { it.lastModified() } ?: 0L)
            .put("l4_checkpoint_exists", checkpoint.isFile)
            .put("l4_checkpoint_at", if (checkpoint.isFile) checkpoint.lastModified() else 0L)
            .put("automatic_count", automaticStatus.optInt("count", 0))
            .put("automatic_maximum", automaticStatus.optInt("maximum", 14))
            .put("automatic_latest_file", automaticStatus.opt("latest_file") ?: JSONObject.NULL)
            .put("automatic_latest_at", automaticStatus.optLong("latest_at", 0L))
            .put("latest_copy_at", maxOf(
                rollingFiles.maxOfOrNull { it.lastModified() } ?: 0L,
                automaticStatus.optLong("latest_at", 0L)
            )))

        val l0 = File(root, "memory/L0_raw.jsonl")
        val l4 = File(root, "memory/L4_state.json")
        result.put("memory", JSONObject()
            .put("l0_size_bytes", if (l0.isFile) l0.length() else 0L)
            .put("l0_size_kb", if (l0.isFile) l0.length() / 1024L else 0L)
            .put("l0_last_append_at", FileManager.lastAppendTimestamp(root))
            .put("l1_facts_count", runCatching {
                JSONObject(FileManager.read(root, "memory/L1_facts.json")).optJSONArray("facts")?.length() ?: 0
            }.getOrDefault(0))
            .put("l4_exists", l4.isFile)
            .put("route_meta_exists", File(root, RouteMeta.PATH).isFile))
        return result.toString(2)
    }
}
