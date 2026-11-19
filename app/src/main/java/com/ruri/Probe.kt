package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Cheap reachability heartbeats; full capability probes retain their own daily quota guard. */
object Probe {
    const val REACH_TTL_MS = 10L * 60L * 1000L
    const val HEARTBEAT_MS = 20L * 60L * 1000L
    const val FULL_PROBE_MS = 24L * 60L * 60L * 1000L
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
            if (!model.optBoolean("free_tier", false) || !model.optBoolean("enabled", true)) continue
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

    internal fun recoveryRefreshAllowed(last: Long, now: Long): Boolean = RouteRecovery.shouldRefresh(last, now)

    fun heartbeat(root: File, config: JSONObject, now: Long = System.currentTimeMillis()) {
        val global = RouteMeta.route(root, "_global")
        if (now - global.optLong("last_heartbeat_at", 0L) < HEARTBEAT_MS) return
        RouteMeta.update(root, "_global") { it.put("last_heartbeat_at", now) }
        val models = config.optJSONArray("models") ?: JSONArray()
        refreshReachability(root, models, topOnly = true)
    }

    fun fullProbeIfDue(root: File, config: JSONObject) {
        if (!NetState.online) return
        RouteCapabilityProbe.refreshIfDue(root, config)
    }
}
