package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class Task { CHAT, WARMTH, REASON, CREATIVE, VISION, TOOLS, CODE, LONG }

/** Selects enabled free routes by manual priority, with explicit NSFW and vision overrides. */
object RouteSelector {
    fun detectTask(text: String, hasImage: Boolean = false): Task = when {
        hasImage -> Task.VISION
        Regex("(?i)(напиши|почини|пофикси|перепиши|отрефакторь)\\s+(код|функцию|класс|скрипт|метод)|\\.(kt|py|js|ts|java)\\b|kotlin|gradle|stacktrace").containsMatchIn(text) -> Task.CODE
        Regex("(?i)(код|функци|докажи|реши|интеграл|алгоритм|debug|ошибк|тест)").containsMatchIn(text) -> Task.REASON
        Regex("(?i)(напиши сцен|рассказ|стих|роль|отыгра|истори)").containsMatchIn(text) -> Task.CREATIVE
        Regex("(?i)(плохо|тяжело|больно|устал|хуев|грустно|обними|рядом|одиноко)").containsMatchIn(text) -> Task.WARMTH
        Regex("(?i)(найди в интернет|погугли|search|инструмент|tool)").containsMatchIn(text) -> Task.TOOLS
        else -> Task.CHAT
    }

    fun detectTaskWithContext(text: String, hasImage: Boolean = false, estimatedTokens: Int = 0): Task {
        val base = detectTask(text, hasImage)
        return if (base != Task.VISION && estimatedTokens > 130000) Task.LONG else base
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

    fun latestCommanderText(root: File): String =
        FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 300)
            .asReversed().asSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .firstOrNull { it.optString("type") == "message" && it.optString("who") == "commander" }
            ?.optString("text").orEmpty()

    fun orderedIndices(
        root: File,
        models: JSONArray,
        current: Int,
        task: Task,
        online: Boolean = NetState.online,
        vpn: Boolean = NetState.vpn,
        adult: Boolean = false
    ): List<Int> {
        val eligible = (0 until models.length()).filter { index ->
            val model = models.optJSONObject(index) ?: return@filter false
            model.optBoolean("free_tier", true) && model.optBoolean("enabled", true)
        }
        var order = eligible.sortedWith(
            compareBy<Int> { models.optJSONObject(it)?.optInt("priority", 99) ?: 99 }
                .thenBy { it }
        )
        val config = runCatching {
            JSONObject(SecretStore.readRuntimeConfig(root))
        }.getOrElse {
            runCatching { JSONObject(FileManager.read(root, "core/config.json")) }.getOrElse { JSONObject() }
        }
        val taskKey = when (task) {
            Task.VISION -> "vision"; Task.WARMTH -> "warmth"; Task.CODE -> "code"
            Task.LONG -> "long"; Task.REASON -> "thought"; else -> "chat"
        }
        val seq = config.optJSONObject("route_tasks")?.optJSONArray(taskKey)
        if (seq != null) {
            fun pos(id: String): Int { for (i in 0 until seq.length()) if (seq.optString(i) == id) return i; return 99 }
            order = order.sortedBy { pos(models.optJSONObject(it)?.optString("id").orEmpty()) }
        }
        if (task == Task.VISION) {
            order = order.filter { models.optJSONObject(it)?.optBoolean("vision", false) == true }
        } else if (adult && config.optBoolean("nsfw_override", true)) {
            val nsfw = order.filter { models.optJSONObject(it)?.optBoolean("nsfw", false) == true }
            val other = order.filter { models.optJSONObject(it)?.optBoolean("nsfw", false) != true }
            order = nsfw + other
        }

        val mode = config.optString("route_mode", "AUTO").uppercase()
        val selectedId = config.optString("selected_route_id").ifBlank {
            models.optJSONObject(current)?.optString("id").orEmpty()
        }
        val selectedIndex = (0 until models.length()).firstOrNull { index ->
            models.optJSONObject(index)?.optString("id") == selectedId
        }
        return when (mode) {
            "MANUAL" -> order.filter { it == selectedIndex }
            "MANUAL_FALLBACK" -> {
                val selected = order.filter { it == selectedIndex }
                selected + order.filter { it != selectedIndex }
            }
            else -> order // AUTO: manual priority order, then fallback on actual request failure.
        }
    }

    internal fun applyManualRouteOrder(config: JSONObject, payload: JSONObject) {
        val models = config.optJSONArray("models") ?: JSONArray()
        val routes = payload.optJSONArray("routes") ?: JSONArray()
        for (i in 0 until routes.length()) {
            val route = routes.optJSONObject(i) ?: continue
            val id = route.optString("id")
            if (id.isBlank()) continue
            for (j in 0 until models.length()) {
                val model = models.optJSONObject(j) ?: continue
                if (model.optString("id") == id) {
                    model.put("priority", route.optInt("priority", 99).coerceAtLeast(1))
                    model.put("nsfw", route.optBoolean("nsfw", false))
                    model.put("vision", route.optBoolean("vision", false))
                }
            }
        }
        config.put("nsfw_override", payload.optBoolean("nsfw_override", true))
        val mode = payload.optString("route_mode", config.optString("route_mode", "AUTO")).uppercase()
        require(mode in setOf("AUTO", "MANUAL", "MANUAL_FALLBACK")) { "Unknown route mode" }
        config.put("route_mode", mode)
        val selectedId = payload.optString("selected_route_id", config.optString("selected_route_id"))
        if (selectedId.isNotBlank() && (0 until models.length()).any {
                models.optJSONObject(it)?.optString("id") == selectedId
            }) {
            config.put("selected_route_id", selectedId)
            config.put("current", (0 until models.length()).first { models.optJSONObject(it)?.optString("id") == selectedId })
        }
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
