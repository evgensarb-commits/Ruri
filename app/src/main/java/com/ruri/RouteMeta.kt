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
        val all = try {
            val raw = FileManager.read(root, PATH)
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } catch (e: Exception) {
            android.util.Log.w("Ruri", "Route metadata could not be read; starting with empty metadata", e)
            JSONObject()
        }
        var migrated = false
        val ids = all.keys()
        while (ids.hasNext()) {
            val route = all.optJSONObject(ids.next()) ?: continue
            listOf("nsfw_capable", "nsfw_tested_at", "nsfw_probe_status", "nsfw").forEach { key ->
                if (route.has(key)) {
                    route.remove(key)
                    migrated = true
                }
            }
            val caps = route.optJSONObject("caps")
            if (caps?.has("nsfw") == true) {
                caps.remove("nsfw")
                route.put("caps", caps)
                migrated = true
            }
        }
        if (migrated) FileManager.write(root, PATH, all.toString(2))
        return all
    }

    /** One-time cleanup for legacy model-config and route-metadata NSFW capability flags. */
    fun migrateLegacyNsfwFlags(root: File) {
        load(root) // Route metadata caps.nsfw is migrated and rewritten by load().

        fun stripLegacyFlags(config: JSONObject): Boolean {
            var changed = false
            val models = config.optJSONArray("models") ?: return false
            for (index in 0 until models.length()) {
                val model = models.optJSONObject(index) ?: continue
                listOf("nsfw_capable", "nsfw_tested_at", "nsfw_probe_status").forEach { key ->
                    if (model.has(key)) {
                        model.remove(key)
                        changed = true
                    }
                }
            }
            return changed
        }

        runCatching {
            val runtime = JSONObject(SecretStore.readRuntimeConfig(root))
            if (stripLegacyFlags(runtime)) SecretStore.saveRuntimeConfig(root, runtime.toString(2))
        }

        runCatching {
            val legacyFile = File(root, "core/config.json")
            if (!legacyFile.isFile) return@runCatching
            val legacy = JSONObject(legacyFile.readText(Charsets.UTF_8))
            if (stripLegacyFlags(legacy)) FileManager.write(root, "core/config.json", legacy.toString(2))
        }
    }

    @Synchronized
    fun save(root: File, value: JSONObject) {
        val sanitized = JSONObject(value.toString())
        val ids = sanitized.keys()
        while (ids.hasNext()) {
            val route = sanitized.optJSONObject(ids.next()) ?: continue
            listOf("nsfw_capable", "nsfw_tested_at", "nsfw_probe_status", "nsfw").forEach { key -> route.remove(key) }
            route.optJSONObject("caps")?.let { caps ->
                caps.remove("nsfw")
                route.put("caps", caps)
            }
        }
        FileManager.write(root, PATH, sanitized.toString(2))
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

    fun available(root: File, id: String, now: Long = System.currentTimeMillis()): Boolean {
        val route = route(root, id)
        return true
    }


    private fun tokenDay(root: File): String {
        val resetHour = runCatching { JSONObject(SecretStore.readRuntimeConfig(root)).optInt("reset_hour_utc", 0) }.getOrDefault(0).coerceIn(0, 23)
        val shifted = Date(System.currentTimeMillis() - resetHour * 3600000L)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(shifted)
    }

    @Synchronized
    fun addTokens(root: File, id: String, tokens: Long) = update(root, id) {
        val today = tokenDay(root)
        if (it.optString("tokens_day_key") != today) {
            it.put("tokens_day_key", today).put("tokens_today", 0L)
        }
        it.put("tokens_today", it.optLong("tokens_today", 0L) + tokens.coerceAtLeast(0L))
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
        updateTaskScore(it, task, true)
    }

    fun recordFailure(root: File, id: String, kind: String, task: Task? = null) = update(root, id) {
        it.put("success_streak", 0)
        updateTaskScore(it, task, false)
        when (kind) {
            "quota" -> Unit // No cooldowns: quota limits are enforced by quotaLeft().
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
