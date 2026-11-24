package com.ruri

import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Task label kept for durable telemetry in [RouteMeta].
 * There is no routing between routes any more: the app talks to one provider.
 */
enum class Task { CHAT, WARMTH, REASON, CREATIVE, VISION, TOOLS, CODE, LONG }

/** Failure classification and adult-topic helpers for the single route. */
object RouteSelector {

    fun latestCommanderText(root: File): String =
        FileManager.readLastLines(root, "memory/L0_raw.jsonl", maxLines = 300)
            .asReversed().asSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .firstOrNull { it.optString("type") == "message" && it.optString("who") == "commander" }
            ?.optString("text").orEmpty()

    fun score(meta: JSONObject, task: Task): Double =
        meta.optJSONObject("scores")?.optDouble(task.name, 0.5)?.coerceIn(0.0, 1.0) ?: 0.5

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

    /**
     * Maps a provider failure to one stable machine token. The important distinction is
     * between a per-minute rate limit (worth retrying in ten seconds) and an exhausted
     * daily token quota (nothing to retry until it resets).
     */
    fun classify(code: Int?, error: Exception?, responseBody: String = ""): String {
        val body = responseBody.lowercase()
        // Explicit quota wording is decisive; otherwise a rate-limit marker wins, so that
        // "RPM limit reached" stays a per-minute limit rather than a daily quota.
        val strongQuota = listOf(
            "quota", "tokens per day", "daily limit", "token limit", "out of credits", "insufficient balance"
        ).any(body::contains)
        val weakQuota = listOf("limit reached", "exhausted", "balance", "credits").any(body::contains)
        val rateHint = listOf("rate", "retry", "rpm", "per-minute", "per minute", "too many").any(body::contains)
        val regionHint = listOf(
            "region", "country", "location", "not available in your", "unsupported territory"
        ).any(body::contains)
        return when {
            code == 429 && strongQuota -> "quota"
            code == 429 && rateHint -> "rate_limit"
            code == 429 && weakQuota -> "quota"
            code == 429 -> "rate_limit"
            (code == 403 || code == 451) && (strongQuota || weakQuota) -> "quota"
            (code == 403 || code == 451) && regionHint -> "region"
            code == 451 -> "region"
            code == 403 -> "forbidden"
            code == 401 -> "auth"
            code == 402 -> "no_credits"
            code == 408 -> "timeout"
            code != null && code in 500..599 -> "server"
            code != null && code in 400..499 -> "bad_request"
            error is UnknownHostException || error is ConnectException || error is SSLException -> "network"
            error is SocketTimeoutException -> "timeout"
            else -> "other"
        }
    }
}
