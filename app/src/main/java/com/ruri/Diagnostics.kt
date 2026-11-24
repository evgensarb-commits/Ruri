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

    /**
     * Live diagnosis: performs one authenticated request per enabled route and reports
     * what the provider answers now. Never returns remembered data for a route the
     * config no longer contains.
     */
    fun verifyAll(ctx: Context): String {
        val root = ctx.filesDir
        NetState.refresh(ctx.applicationContext)
        val config = runCatching { JSONObject(SecretStore.readRuntimeConfig(root)) }
            .getOrDefault(JSONObject())
        val models = config.optJSONArray("models") ?: JSONArray()
        if (!NetState.online) {
            return JSONObject().put("online", false)
                .put("routes", JSONObject()).toString()
        }
        val report = Probe.verify(root, models)
        return JSONObject().put("online", true).put("routes", report).toString()
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
            val sameDay = meta.optString("day_key") == utcDay
            val dayCount = if (sameDay) meta.optInt("day_count", 0) else 0
            val route = JSONObject()
                .put("name", model.optString("name", id))
                .put("model", model.optString("model"))
                .put("enabled", model.optBoolean("enabled", true))
                .put("free_tier", model.optBoolean("free_tier", false))
                .put("has_key", model.optString("api_key").isNotBlank())
                .put("api_key_configured", model.optString("api_key").isNotBlank())
                .put("reachable", meta.optBoolean("reachable", true))
                .put("needs_vpn", meta.optBoolean("needs_vpn", model.optBoolean("needs_vpn", false)))
                .put("in_cooldown", meta.optLong("cooldown_until", 0L) > now)
                .put("cooldown_until", meta.optLong("cooldown_until", 0L))
                .put("requests_today", dayCount)
                .put("requests_lifetime", maxOf(meta.optLong("lifetime_requests", dayCount.toLong()), dayCount.toLong()))
                .put("successes_today", if (sameDay) meta.optInt("successes_today", 0) else 0)
                .put("failures_today", if (sameDay) meta.optInt("failures_today", 0) else 0)
                .put("rate_limits_today", if (sameDay) meta.optInt("rate_limits_today", 0) else 0)
                .put("network_errors_today", if (sameDay) meta.optInt("network_errors_today", 0) else 0)
                .put("region_restrictions_today", if (sameDay) meta.optInt("region_restrictions_today", 0) else 0)
                .put("timeouts_today", if (sameDay) meta.optInt("timeouts_today", 0) else 0)
                .put("http_responses_today", if (sameDay) meta.optInt("http_responses_today", 0) else 0)
                .put("http_4xx_today", if (sameDay) meta.optInt("http_4xx_today", 0) else 0)
                .put("http_5xx_today", if (sameDay) meta.optInt("http_5xx_today", 0) else 0)
                .put("last_http_status", if (meta.has("last_http_status")) meta.opt("last_http_status") else JSONObject.NULL)
                .put("last_failure_kind", meta.optString("last_failure_kind"))
                .put("requests_per_day", model.optInt("rpd", 200))
                .put("quota_remaining", (model.optInt("rpd", 200) - dayCount).coerceAtLeast(0))
                .put("last_reachability_at", meta.optLong("last_reachability_at", 0L))
                .put("chat_score", RouteSelector.score(meta, Task.CHAT))
                .put("last_attempt", meta.optLong("last_attempt", 0L))
                .put("last_success", meta.optLong("last_ok", 0L))
            routes.put(id, route)
        }
        var requestsTodayTotal = 0
        var requestsLifetimeTotal = 0L
        var successesTodayTotal = 0
        var failuresTodayTotal = 0
        var rateLimitsTodayTotal = 0
        var networkErrorsTodayTotal = 0
        var regionRestrictionsTodayTotal = 0
        var timeoutsTodayTotal = 0
        var http4xxTodayTotal = 0
        var http5xxTodayTotal = 0
        val routeIds = routes.keys()
        while (routeIds.hasNext()) {
            val route = routes.optJSONObject(routeIds.next()) ?: continue
            requestsTodayTotal += route.optInt("requests_today", 0)
            requestsLifetimeTotal += route.optLong("requests_lifetime", 0L)
            successesTodayTotal += route.optInt("successes_today", 0)
            failuresTodayTotal += route.optInt("failures_today", 0)
            rateLimitsTodayTotal += route.optInt("rate_limits_today", 0)
            networkErrorsTodayTotal += route.optInt("network_errors_today", 0)
            regionRestrictionsTodayTotal += route.optInt("region_restrictions_today", 0)
            timeoutsTodayTotal += route.optInt("timeouts_today", 0)
            http4xxTodayTotal += route.optInt("http_4xx_today", 0)
            http5xxTodayTotal += route.optInt("http_5xx_today", 0)
        }
        result.put("model_requests", JSONObject()
            .put("today_total", requestsTodayTotal)
            .put("lifetime_total", requestsLifetimeTotal)
            .put("successes_today", successesTodayTotal)
            .put("failures_today", failuresTodayTotal)
            .put("rate_limits_429_today", rateLimitsTodayTotal)
            .put("network_errors_today", networkErrorsTodayTotal)
            .put("region_restrictions_today", regionRestrictionsTodayTotal)
            .put("timeouts_today", timeoutsTodayTotal)
            .put("http_4xx_today", http4xxTodayTotal)
            .put("http_5xx_today", http5xxTodayTotal))
        result.put("routes", routes)
        result.put("current_route", config.optInt("current", -1))
        result.put("exclusive_route", config.optBoolean("exclusive_route", false))
        val latestNetwork = runCatching { JSONObject(NetworkDiagnostics.latest(root)) }.getOrDefault(JSONObject())
        result.put("last_provider", latestNetwork.optString("provider"))
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
