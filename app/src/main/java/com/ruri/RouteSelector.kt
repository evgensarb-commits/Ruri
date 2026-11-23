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
            JSONObject(FileManager.read(root, "core/config.json"))
        }.getOrElse { JSONObject() }
        if (task == Task.VISION) {
            order = order.filter { models.optJSONObject(it)?.optBoolean("vision", false) == true }
        } else if (adult && config.optBoolean("nsfw_override", true)) {
            val nsfw = order.filter { models.optJSONObject(it)?.optBoolean("nsfw", false) == true }
            val other = order.filter { models.optJSONObject(it)?.optBoolean("nsfw", false) != true }
            order = nsfw + other
        }
        return order
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
