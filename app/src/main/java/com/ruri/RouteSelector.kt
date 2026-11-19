package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class Task { CHAT, WARMTH, REASON, CREATIVE, VISION, TOOLS }

/** Selects only explicitly free routes using reachability, quota, VPN, capability, and task score. */
object RouteSelector {
    fun detectTask(text: String, hasImage: Boolean = false): Task = when {
        hasImage -> Task.VISION
        Regex("(?i)(код|функци|докажи|реши|интеграл|алгоритм|debug|ошибк|тест)").containsMatchIn(text) -> Task.REASON
        Regex("(?i)(напиши сцен|рассказ|стих|роль|отыгра|истори)").containsMatchIn(text) -> Task.CREATIVE
        Regex("(?i)(плохо|тяжело|больно|устал|хуев|грустно|обними|рядом|одиноко)").containsMatchIn(text) -> Task.WARMTH
        Regex("(?i)(найди в интернет|погугли|search|инструмент|tool)").containsMatchIn(text) -> Task.TOOLS
        else -> Task.CHAT
    }

    fun nsfwWindow(root: File): Boolean {
        val joined = FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 12)
            .asReversed()
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { it.optString("type") == "message" }
            .take(6)
            .joinToString(" ") { it.optString("text") }
        return RouteCapabilityProbe.isAdultTopic(joined)
    }

    fun score(meta: JSONObject, task: Task): Double =
        meta.optJSONObject("scores")?.optDouble(task.name, 0.5)?.coerceIn(0.0, 1.0) ?: 0.5

    fun ordered(
        root: File,
        models: JSONArray,
        current: Int,
        task: Task,
        needNsfw: Boolean,
        online: Boolean = NetState.online,
        vpn: Boolean = NetState.vpn
    ): List<Int> {
        if (!online) return emptyList()
        val now = System.currentTimeMillis()
        val candidates = mutableListOf<Pair<Int, Double>>()
        for (index in 0 until models.length()) {
            val model = models.optJSONObject(index) ?: continue
            if (!model.optBoolean("free_tier", false) || !model.optBoolean("enabled", true)) continue
            val id = model.optString("id").ifBlank { model.optString("name").ifBlank { "route_$index" } }
            val keyAvailable = model.optString("api_key").isNotBlank() ||
                model.optBoolean("allow_anonymous", false)
            if (!keyAvailable) continue

            val meta = RouteMeta.route(root, id)
            if (model.has("needs_vpn") && model.optBoolean("needs_vpn")) {
                RouteMeta.setNeedsVpn(root, id, true)
            }
            val needsVpn = RouteMeta.route(root, id).optBoolean("needs_vpn", false)
            if (needsVpn && !vpn) continue
            if (!vpn && !RouteMeta.route(root, id).optBoolean("ru_reachable", true)) continue
            if (!RouteMeta.available(root, id, now)) continue
            if (needNsfw) {
                val tested = model.optLong("nsfw_tested_at", 0L) > 0L &&
                    model.optString("nsfw_probe_status") == "passed" &&
                    model.optBoolean("nsfw_capable", false)
                if (!tested || !meta.optJSONObject("caps")?.optBoolean("nsfw", true)!!) continue
            }
            if (!RouteMeta.quotaLeft(root, id, model.optInt("rpd", 200))) continue
            candidates += index to score(meta, task)
        }
        return candidates.sortedWith(
            compareByDescending<Pair<Int, Double>> { it.second }
                .thenBy { if (it.first == current) 0 else 1 }
                .thenBy { models.optJSONObject(it.first)?.optInt("priority", 100) ?: 100 }
                .thenByDescending {
                    val model = models.optJSONObject(it.first)
                    RouteMeta.route(root, model?.optString("id").orEmpty()).optLong("last_ok", 0L)
                }
        ).map { it.first }
    }

    fun classify(code: Int?, error: Exception?, responseBody: String = ""): String {
        val body = responseBody.lowercase()
        return when {
            code == 429 -> "quota"
            (code == 403 || code == 451) &&
                listOf("region", "country", "location", "not available in your", "unsupported territory")
                    .any(body::contains) -> "region"
            code != null && code in 500..599 -> "server"
            error is UnknownHostException || error is ConnectException || error is SSLException -> "network"
            error is SocketTimeoutException -> "timeout"
            else -> "other"
        }
    }
}
