package com.ruri

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
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

    private fun nextUtcMidnight(): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.add(Calendar.DAY_OF_YEAR, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Synchronized
    fun load(root: File): JSONObject = try {
        val raw = FileManager.read(root, PATH)
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    } catch (e: Exception) {
        android.util.Log.w("Ruri", "Route metadata could not be read; starting with empty metadata", e)
        JSONObject()
    }

    @Synchronized
    fun save(root: File, value: JSONObject) {
        FileManager.write(root, PATH, value.toString(2))
    }

    private fun defaultRoute(): JSONObject {
        val scores = JSONObject()
        Task.values().forEach { scores.put(it.name, 0.5) }
        return JSONObject()
            .put("fail_streak", 0)
            .put("success_streak", 0)
            .put("cooldown_until", 0L)
            .put("day_count", 0)
            .put("day_key", utcDay())
            .put("last_ok", 0L)
            .put("last_attempt", 0L)
            .put("last_reachability_at", 0L)
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

    fun setCapability(root: File, id: String, capability: String, capable: Boolean) =
        update(root, id) {
            val caps = it.optJSONObject("caps") ?: JSONObject()
            caps.put(capability, capable)
            it.put("caps", caps)
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
        return current.optInt("day_count", 0) < requestsPerDay.coerceAtLeast(1)
    }

    fun available(root: File, id: String, now: Long = System.currentTimeMillis()): Boolean {
        val route = route(root, id)
        return route.optLong("cooldown_until", 0L) <= now &&
            route.optBoolean("reachable", true)
    }

    /** Counts every outbound provider request, including probes and unsuccessful attempts. */
    fun recordAttempt(root: File, id: String) = update(root, id) {
        val today = utcDay()
        if (it.optString("day_key") != today) {
            it.put("day_key", today)
            it.put("day_count", 0)
        }
        it.put("day_count", it.optInt("day_count", 0) + 1)
        it.put("last_attempt", System.currentTimeMillis())
    }

    fun recordSuccess(root: File, id: String, task: Task? = null) = update(root, id) {
        it.put("fail_streak", 0)
        val streak = it.optInt("success_streak", 0) + 1
        it.put("success_streak", streak)
        it.put("last_ok", System.currentTimeMillis())
        if (streak >= 2) it.put("cooldown_until", 0L)
        updateTaskScore(it, task, true)
    }

    fun recordFailure(root: File, id: String, kind: String, task: Task? = null) = update(root, id) {
        it.put("success_streak", 0)
        updateTaskScore(it, task, false)
        when (kind) {
            "quota" -> it.put("cooldown_until", nextUtcMidnight())
            "network" -> {
                it.put("reachable", false)
                if (!NetState.vpn) it.put("ru_reachable", false)
            }
            "region" -> {
                it.put("needs_vpn", true)
                it.put("ru_reachable", false)
                it.put("reachable", false)
            }
            "refusal" -> Unit // NSFW capability is updated by RouteCapabilityProbe.
            else -> {
                val streak = it.optInt("fail_streak", 0) + 1
                it.put("fail_streak", streak)
                if (streak >= 2) {
                    it.put("cooldown_until", System.currentTimeMillis() + 10L * 60L * 1000L)
                }
            }
        }
    }

    private fun updateTaskScore(route: JSONObject, task: Task?, success: Boolean) {
        if (task == null) return
        val scores = route.optJSONObject("scores") ?: JSONObject()
        val previous = scores.optDouble(task.name, 0.5).coerceIn(0.0, 1.0)
        val observed = if (success) 1.0 else 0.0
        scores.put(task.name, (previous * 0.8 + observed * 0.2).coerceIn(0.0, 1.0))
        route.put("scores", scores)
    }
}
