package com.ruri

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Durable health, quota, capability, and per-task performance state for each free route. */
object RouteMeta {
    const val PATH = "memory/route_meta.json"

    private fun utcDay(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    @Synchronized
    fun load(root: File): JSONObject {
        return try {
            val raw = FileManager.read(root, PATH)
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } catch (e: Exception) {
            android.util.Log.w("Ruri", "Route metadata could not be read; starting with empty metadata", e)
            JSONObject()
        }
    }

    @Synchronized
    fun save(root: File, value: JSONObject) {
        FileManager.write(root, PATH, JSONObject(value.toString()).toString(2))
    }

    private fun defaultRoute(): JSONObject {
        val scores = JSONObject()
        Task.values().forEach { scores.put(it.name, 0.5) }
        return JSONObject()
            .put("fail_streak", 0)
            .put("success_streak", 0)
            .put("day_count", 0)
            .put("day_key", utcDay())
            .put("last_ok", 0L)
            .put("last_attempt", 0L)
            .put("last_reachability_at", 0L)
            .put("task_success_streaks", JSONObject())
            .put("reachable", true)
            .put("needs_vpn", false)
            .put("ru_reachable", true)
            .put("caps", JSONObject())
            .put("scores", scores)
    }

    @Synchronized
    fun route(root: File, id: String): JSONObject {
        require(id.isNotBlank()) { "Route ID must not be blank" }
        val all = load(root)
        val existing = all.optJSONObject(id)
        if (existing != null) return JSONObject(existing.toString())
        val created = defaultRoute()
        all.put(id, created)
        save(root, all)
        return JSONObject(created.toString())
    }

    @Synchronized
    fun update(root: File, id: String, mutator: (JSONObject) -> Unit) {
        require(id.isNotBlank()) { "Route ID must not be blank" }
        val all = load(root)
        val route = all.optJSONObject(id) ?: defaultRoute()
        mutator(route)
        all.put(id, route)
        save(root, all)
    }

    fun setReachable(root: File, id: String, reachable: Boolean, viaVpn: Boolean = false) {
        update(root, id) {
            it.put("reachable", reachable)
            it.put("last_reachability_at", System.currentTimeMillis())
            if (!viaVpn) it.put("ru_reachable", reachable)
        }
    }

    fun setNeedsVpn(root: File, id: String, needsVpn: Boolean) =
        update(root, id) { it.put("needs_vpn", needsVpn) }

    fun setCapability(root: File, id: String, capability: String, capable: Boolean) {
        require(!capability.equals("nsfw", ignoreCase = true)) {
            "NSFW route capabilities are not persisted"
        }
        update(root, id) {
            val caps = it.optJSONObject("caps") ?: JSONObject()
            caps.put(capability, capable)
            it.put("caps", caps)
        }
    }

    @Synchronized
    fun quotaLeft(root: File, id: String, requestsPerDay: Int): Boolean {
        val today = utcDay()
        var current = route(root, id)
        if (current.optString("day_key") != today) {
            update(root, id) {
                it.put("day_count", 0)
                it.put("day_key", today)
            }
            current = route(root, id)
        }
        return true
    }

    fun available(root: File, id: String, now: Long = System.currentTimeMillis()): Boolean = true

    /**
     * Soft daily budget for **autonomous** cognition only.
     *
     * This is deliberately not a request gate: the Commander's chat always goes through, even
     * on an exhausted budget (that is the no-preflight rule). It exists so her own idle
     * thoughts cannot burn the whole daily allowance and leave the Commander without a reply.
     */
    fun autonomyBudgetAvailable(
        root: File,
        id: String = Route.ID,
        maxDailyRequests: Int = 40,
        maxDailyTokens: Long = 250_000L
    ): Boolean {
        val route = runCatching { route(root, id) }.getOrNull() ?: return true
        val today = utcDay()
        val requests = if (route.optString("day_key") == today) route.optInt("day_count", 0) else 0
        val tokens = tokensToday(root, id)
        return requests < maxDailyRequests && tokens < maxDailyTokens
    }

    /** Records provider quota metadata for display/scoring only; never affects eligibility. */
    fun markQuotaExhausted(root: File, id: String, until: Long = 0L) = update(root, id) {
        it.put("quota_exhausted_until", until)
    }


    private fun tokenDay(root: File, id: String): String {
        val resetHour = runCatching {
            val models = JSONObject(SecretStore.readRuntimeConfig(root)).optJSONArray("models")
            (0 until (models?.length() ?: 0)).mapNotNull { models?.optJSONObject(it) }
                .firstOrNull { it.optString("id") == id }?.optInt("reset_hour_utc", 0) ?: 0
        }.getOrDefault(0).coerceIn(0, 23)
        val shifted = Date(System.currentTimeMillis() - resetHour * 3600000L)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(shifted)
    }

    @Synchronized
    fun addTokens(root: File, id: String, tokens: Long) = update(root, id) {
        val today = tokenDay(root, id)
        if (it.optString("tokens_day_key") != today) {
            it.put("tokens_day_key", today).put("tokens_today", 0L)
        }
        it.put("tokens_today", it.optLong("tokens_today", 0L) + tokens.coerceAtLeast(0L))
    }

    fun tokensToday(root: File, id: String): Long {
        val route = runCatching { route(root, id) }.getOrNull() ?: return 0L
        return if (route.optString("tokens_day_key") == tokenDay(root, id)) route.optLong("tokens_today", 0L) else 0L
    }

    /** Counts every outbound provider request, including probes and unsuccessful attempts. */
    fun recordAttempt(root: File, id: String) = update(root, id) {
        val today = utcDay()
        if (it.optString("day_key") != today) {
            it.put("day_key", today)
            it.put("day_count", 0)
            it.put("successes_today", 0)
            it.put("failures_today", 0)
            it.put("rate_limits_today", 0)
            it.put("network_errors_today", 0)
            it.put("region_restrictions_today", 0)
            it.put("timeouts_today", 0)
            it.put("http_responses_today", 0)
            it.put("http_4xx_today", 0)
            it.put("http_5xx_today", 0)
            it.put("last_http_status", JSONObject.NULL)
        }
        it.put("day_count", it.optInt("day_count", 0) + 1)
        it.put("lifetime_requests", it.optLong("lifetime_requests", 0L) + 1L)
        it.put("last_attempt", System.currentTimeMillis())
    }

    fun recordHttpStatus(root: File, id: String, status: Int) = update(root, id) {
        it.put("last_http_status", status)
            .put("http_responses_today", it.optInt("http_responses_today", 0) + 1)
        if (status == 429) it.put("rate_limits_today", it.optInt("rate_limits_today", 0) + 1)
        if (status in 400..499) it.put("http_4xx_today", it.optInt("http_4xx_today", 0) + 1)
        if (status in 500..599) it.put("http_5xx_today", it.optInt("http_5xx_today", 0) + 1)
    }

    fun recordSuccess(root: File, id: String, task: Task? = null) = update(root, id) {
        it.put("successes_today", it.optInt("successes_today", 0) + 1)
        it.put("fail_streak", 0)
        val streak = it.optInt("success_streak", 0) + 1
        it.put("success_streak", streak)
        it.put("last_ok", System.currentTimeMillis())
        updateTaskScore(it, task, true)
    }

    fun recordFailure(root: File, id: String, kind: String, task: Task? = null) = update(root, id) {
        it.put("failures_today", it.optInt("failures_today", 0) + 1)
        it.put("last_failure_kind", kind.take(40))
        when (kind) {
            "network" -> it.put("network_errors_today", it.optInt("network_errors_today", 0) + 1)
            "region" -> it.put("region_restrictions_today", it.optInt("region_restrictions_today", 0) + 1)
            "timeout" -> it.put("timeouts_today", it.optInt("timeouts_today", 0) + 1)
        }
        it.put("success_streak", 0)
        updateTaskScore(it, task, false)
        when (kind) {
            "quota", "rate_limit" -> Unit // Provider limits are telemetry/scoring only; never block eligibility.
            "network", "timeout" -> {
                it.put("reachable", false)
                if (!NetState.vpn) it.put("ru_reachable", false)
            }
            "region" -> {
                it.put("needs_vpn", true)
                it.put("ru_reachable", false)
                it.put("reachable", false)
            }
            "refusal" -> Unit // Refusals are logged; they never permanently change route eligibility.
            else -> {
                val streak = it.optInt("fail_streak", 0) + 1
                it.put("fail_streak", streak)
            }
        }
    }

    private fun updateTaskScore(route: JSONObject, task: Task?, success: Boolean) {
        if (task == null) return
        val scores = route.optJSONObject("scores") ?: JSONObject()
        val streaks = route.optJSONObject("task_success_streaks") ?: JSONObject()
        val previous = scores.optDouble(task.name, 0.5).coerceIn(0.0, 1.0)
        val observed = if (success) 1.0 else 0.0
        val next = (previous * 0.8 + observed * 0.2).coerceIn(0.0, 1.0)
        val streak = if (success) streaks.optInt(task.name, 0) + 1 else 0
        streaks.put(task.name, streak)
        // Two consecutive wins on the same task establish a meaningful promotion signal.
        scores.put(task.name, if (streak >= 2) maxOf(next, 0.75) else next)
        route.put("scores", scores)
        route.put("task_success_streaks", streaks)
    }
}
