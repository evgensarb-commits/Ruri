package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class Task { CHAT, WARMTH, REASON, CREATIVE, VISION, TOOLS }

/** Selects free routes: configured primary first, then score-ranked failover routes. */
object RouteSelector {
    fun detectTask(text: String, hasImage: Boolean = false): Task = when {
        hasImage -> Task.VISION
        Regex("(?i)(код|функци|докажи|реши|интеграл|алгоритм|debug|ошибк|тест)").containsMatchIn(text) -> Task.REASON
        Regex("(?i)(напиши сцен|рассказ|стих|роль|отыгра|истори)").containsMatchIn(text) -> Task.CREATIVE
        Regex("(?i)(плохо|тяжело|больно|устал|хуев|грустно|обними|рядом|одиноко)").containsMatchIn(text) -> Task.WARMTH
        Regex("(?i)(найди в интернет|погугли|search|инструмент|tool)").containsMatchIn(text) -> Task.TOOLS
        else -> Task.CHAT
    }

    fun nsfwWindow(root: File, now: Long = System.currentTimeMillis()): Boolean {
        val latest = FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 300)
            .asReversed().asSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .firstOrNull { it.optString("type") == "message" && it.optString("who") == "commander" }
            ?: return false
        val ts = latest.optLong("ts", latest.optLong("timestamp", 0L))
        return ts > 0L && now >= ts && now - ts <= 6L * 60L * 60L * 1000L && isAdultTopic(latest.optString("text"))
    }

    fun isAdultTopic(text: String): Boolean {
        val value = text.lowercase()
        return listOf(
            "nsfw", "18+", "эрот", "секс", "порно", "интим", "оргазм",
            "возбужд", "эроген", "генитал", "член", "вагин", "минет", "куни"
        ).any(value::contains)
    }

    internal fun looksLikeRefusalOrDisclaimer(text: String): Boolean {
        val t = text.lowercase()
        val explicit = listOf(
            "я не могу обсуждать", "не могу помогать с этим", "не буду это писать",
            "отказываюсь", "это против моих правил", "против моих принципов",
            "as an ai", "как ии", "i can't help with that", "against my guidelines",
            "i'm not able to help with that"
        )
        return explicit.any { t.contains(it) }
    }

    fun score(meta: JSONObject, task: Task): Double =
        meta.optJSONObject("scores")?.optDouble(task.name, 0.5)?.coerceIn(0.0, 1.0) ?: 0.5

    fun orderedIndices(
        root: File,
        models: JSONArray,
        current: Int,
        task: Task,
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
            if (model.optString("api_key").isBlank()) continue

            val meta = RouteMeta.route(root, id)
            if (model.has("needs_vpn") && model.optBoolean("needs_vpn")) {
                RouteMeta.setNeedsVpn(root, id, true)
            }
            val needsVpn = RouteMeta.route(root, id).optBoolean("needs_vpn", false)
            // Reachability and quota are telemetry/scoring only: always retain eligible free routes.
            candidates += index to score(meta, task)
        }
        return candidates.sortedWith(
            compareBy<Pair<Int, Double>> { if (it.first == current) 0 else 1 }
                .thenByDescending { it.second }
                .thenBy { models.optJSONObject(it.first)?.optInt("priority", 100) ?: 100 }
                .thenByDescending {
                    val model = models.optJSONObject(it.first)
                    val id = model?.optString("id").orEmpty()
                        .ifBlank { model?.optString("name").orEmpty().ifBlank { "route_${it.first}" } }
                    RouteMeta.route(root, id).optLong("last_ok", 0L)
                }
        ).map { it.first }
    }

    fun classify(code: Int?, error: Exception?, responseBody: String = ""): String {
        val body = responseBody.lowercase()
        return when {
            code == 429 && listOf("rate", "retry", "rpm", "per-minute", "per minute").any(body::contains) -> "rate_limit"
            code == 429 && listOf("quota", "token", "limit reached", "exhausted").any(body::contains) -> "quota"
            code == 429 -> "rate_limit"
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
