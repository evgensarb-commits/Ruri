package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Cheap reachability heartbeats and explicit route-availability checks. */
object Probe {
    const val REACH_TTL_MS = 10L * 60L * 1000L
    const val HEARTBEAT_MS = 20L * 60L * 1000L
    const val FORCE_REFRESH_THROTTLE_MS = 60_000L
    const val STARTUP_REFRESH_THROTTLE_MS = 300_000L
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 4_000
    @Volatile private var lastRecoveryRefreshAt = 0L

    fun cheapReachable(baseUrl: String): Boolean {
        var connection: HttpsURLConnection? = null
        return try {
            if (!baseUrl.startsWith("https://", ignoreCase = true)) return false
            connection = (URL(baseUrl).openConnection() as HttpsURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            code in 200..499 // Auth-required/404 endpoints still prove host reachability.
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    fun refreshReachability(root: File, models: JSONArray, topOnly: Boolean, force: Boolean = false) {
        if (!NetState.online && !force) return
        val count = if (topOnly) minOf(models.length(), 3) else models.length()
        val now = System.currentTimeMillis()
        for (index in 0 until count) {
            val model = models.optJSONObject(index) ?: continue
            // Probe every route the commander enabled. `free_tier` is telemetry, not a gate:
            // a paid route that is never probed keeps stale metadata forever and the
            // diagnostics screen then shows data that no longer reflects reality.
            if (!model.optBoolean("enabled", true)) continue
            if (model.optString("base_url").isBlank()) continue
            val id = model.optString("id").ifBlank { model.optString("name").ifBlank { "route_$index" } }
            val meta = RouteMeta.route(root, id)
            if (!force && now - meta.optLong("last_reachability_at", 0L) < REACH_TTL_MS) continue
            val reachable = cheapReachable(model.optString("base_url"))
            RouteMeta.setReachable(root, id, reachable, viaVpn = NetState.vpn)
        }
    }

    @Synchronized
    fun claimRecoveryRefresh(now: Long = System.currentTimeMillis()): Boolean {
        if (!recoveryRefreshAllowed(lastRecoveryRefreshAt, now)) return false
        lastRecoveryRefreshAt = now
        return true
    }

    internal fun recoveryRefreshAllowed(last: Long, now: Long): Boolean =
        now - last >= FORCE_REFRESH_THROTTLE_MS

    @Synchronized
    fun claimStartupRefresh(root: File, now: Long = System.currentTimeMillis()): Boolean {
        val global = RouteMeta.route(root, "_global")
        val last = global.optLong("last_startup_refresh_at", 0L)
        if (now - last < STARTUP_REFRESH_THROTTLE_MS) return false
        RouteMeta.update(root, "_global") { it.put("last_startup_refresh_at", now) }
        return true
    }

    fun heartbeat(root: File, config: JSONObject, now: Long = System.currentTimeMillis()) {
        val global = RouteMeta.route(root, "_global")
        if (now - global.optLong("last_heartbeat_at", 0L) < HEARTBEAT_MS) return
        RouteMeta.update(root, "_global") { it.put("last_heartbeat_at", now) }
        val models = config.optJSONArray("models") ?: JSONArray()
        refreshReachability(root, models, topOnly = true)
    }

    /**
     * Real, on-demand diagnosis of every enabled route: an authenticated GET against
     * `<base_url>/models`. Unlike [refreshReachability] this proves that the API key is
     * accepted, so the diagnostics screen reports what the provider answers right now
     * instead of replaying counters that may describe a route that no longer exists.
     */
    fun verify(root: File, models: JSONArray, now: Long = System.currentTimeMillis()): JSONObject {
        val report = JSONObject()
        for (index in 0 until models.length()) {
            val model = models.optJSONObject(index) ?: continue
            val id = model.optString("id").ifBlank { model.optString("name").ifBlank { "route_$index" } }
            val base = model.optString("base_url").trim().trimEnd('/')
            val key = model.optString("api_key")
            val entry = JSONObject()
                .put("id", id)
                .put("name", model.optString("name", id))
                .put("checked_at", now)
                .put("enabled", model.optBoolean("enabled", true))
            if (!model.optBoolean("enabled", true)) {
                entry.put("result", "disabled").put("http_status", JSONObject.NULL)
                report.put(id, entry)
                continue
            }
            if (base.isBlank() || !base.startsWith("https://", ignoreCase = true)) {
                entry.put("result", "no_base_url").put("http_status", JSONObject.NULL)
                report.put(id, entry)
                continue
            }
            if (key.isBlank()) {
                entry.put("result", "no_key").put("http_status", JSONObject.NULL)
                report.put(id, entry)
                // A missing key is a diagnosis worth remembering: the diagnostics
                // screen must show why a route never gets called.
                RouteMeta.update(root, id) { meta ->
                    meta.put("last_verify_at", now)
                    meta.put("last_verify_result", "no_key")
                }
                continue
            }
            val outcome = authenticatedStatus("$base/models", key)
            entry.put("http_status", outcome.first ?: JSONObject.NULL)
                .put("result", outcome.second)
                .put("detail", outcome.third.take(160))
            report.put(id, entry)
            RouteMeta.update(root, id) { meta ->
                meta.put("last_verify_at", now)
                meta.put("last_verify_result", outcome.second)
                if (outcome.first != null) meta.put("last_http_status", outcome.first)
                meta.put("reachable", outcome.second == "ok" || outcome.second == "no_key")
                meta.put("last_reachability_at", now)
            }
        }
        return report
    }

    /** Returns (httpStatus, resultKind, detail). `resultKind` is a stable machine token. */
    private fun authenticatedStatus(url: String, key: String): Triple<Int?, String, String> {
        var connection: HttpsURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            val body = runCatching {
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            val kind = when {
                code in 200..299 -> "ok"
                code == 401 -> "bad_key"
                code == 402 -> "no_credits"
                code == 403 -> "forbidden"
                code == 404 -> "bad_model_path"
                code == 429 -> "rate_limited"
                code in 500..599 -> "provider_error"
                else -> "http_$code"
            }
            Triple(code, kind, body.ifBlank { "HTTP $code" })
        } catch (e: Exception) {
            Triple(null, "network_failed", e.message.orEmpty())
        } finally {
            connection?.disconnect()
        }
    }
}
