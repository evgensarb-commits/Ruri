package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * One HTTPS chat completion against the single route, instrumented stage by stage.
 *
 * The stage list is the primary diagnostic artefact: it shows exactly where a request
 * stops (DNS / CONNECT / WRITE / HTTP / READ / PARSE) and how long each stage took.
 */
object Transport {
    const val CONNECT_TIMEOUT_MS = 18_000
    const val READ_TIMEOUT_MS = 35_000

    sealed class Result {
        abstract val stages: JSONArray
        abstract val totalMs: Long

        class Success(
            val reply: String,
            val httpStatus: Int,
            override val stages: JSONArray,
            override val totalMs: Long
        ) : Result()

        class Failure(
            val kind: String,
            val httpStatus: Int?,
            val detail: String,
            override val stages: JSONArray,
            override val totalMs: Long
        ) : Result()
    }

    fun chat(
        messages: JSONArray,
        apiKey: String,
        maxTokens: Int = 512,
        modelId: String = Route.MODEL_FREE,
        baseUrl: String = Route.ROUTEWAY_BASE_URL
    ): Result {
        val stages = JSONArray()
        val startedAt = System.nanoTime()
        var connection: HttpURLConnection? = null
        var httpStatus: Int? = null
        try {
            val endpoint = runCatching {
                URL(baseUrl.trimEnd('/') + "/chat/completions")
            }.getOrElse {
                addStage(stages, "CONFIG", false, 0L, "неверный адрес: $baseUrl")
                return Result.Failure("config", null, "неверный адрес: $baseUrl", stages, elapsed(startedAt))
            }
            val host = endpoint.host

            if (apiKey.isBlank()) {
                addStage(stages, "KEY", false, 0L, "ключ Routeway не задан")
                return Result.Failure("no_key", null, "ключ не задан", stages, elapsed(startedAt))
            }
            addStage(stages, "KEY", true, 0L, "ключ присутствует (${apiKey.length} символов)")

            var stageAt = System.nanoTime()
            val addresses = try {
                InetAddress.getAllByName(host)
            } catch (e: Exception) {
                val detail = simple(e)
                addStage(stages, "DNS", false, elapsed(stageAt), "не удалось разрешить $host · $detail")
                return Result.Failure("network", null, "DNS не разрешил $host · $detail", stages, elapsed(startedAt))
            }
            addStage(
                stages, "DNS", true, elapsed(stageAt),
                "$host → " + addresses.joinToString(", ") { it.hostAddress.orEmpty() }.take(200)
            )



            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }

            stageAt = System.nanoTime()
            try {
                connection.connect()
            } catch (e: Exception) {
                val kind = if (e is SocketTimeoutException) "timeout" else "network"
                val detail = simple(e)
                addStage(stages, "CONNECT", false, elapsed(stageAt), "TCP/TLS не установлены · $detail")
                return Result.Failure(kind, null, detail, stages, elapsed(startedAt))
            }
            addStage(stages, "CONNECT", true, elapsed(stageAt), "TCP+TLS установлены")

            val body = JSONObject()
                .put("model", modelId)
                .put("messages", messages)
                .put("max_completion_tokens", maxTokens)
                .put("temperature", 0.9)
                .put("presence_penalty", 0.6)
                .put("frequency_penalty", 0.4)

            stageAt = System.nanoTime()
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) {
                val detail = simple(e)
                addStage(stages, "WRITE", false, elapsed(stageAt), "запрос не отправлен · $detail")
                return Result.Failure("network", null, detail, stages, elapsed(startedAt))
            }
            addStage(stages, "WRITE", true, elapsed(stageAt), "${body.toString().length} байт отправлено")

            stageAt = System.nanoTime()
            httpStatus = try {
                connection.responseCode
            } catch (e: Exception) {
                val kind = if (e is SocketTimeoutException) "timeout" else "network"
                val detail = simple(e)
                addStage(stages, "HTTP", false, elapsed(stageAt), "ответ не получен · $detail")
                return Result.Failure(kind, null, detail, stages, elapsed(startedAt))
            }
            addStage(
                stages, "HTTP", httpStatus in 200..299, elapsed(stageAt),
                "HTTP $httpStatus " + runCatching { connection.responseMessage.orEmpty() }.getOrDefault("")
            )

            stageAt = System.nanoTime()
            val text = try {
                val stream = if (httpStatus in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            } catch (e: Exception) {
                val detail = simple(e)
                addStage(stages, "READ", false, elapsed(stageAt), "тело ответа не прочитано · $detail")
                return Result.Failure("network", httpStatus, detail, stages, elapsed(startedAt))
            }
            addStage(stages, "READ", true, elapsed(stageAt), "${text.length} символов")

            val code = httpStatus ?: 0
            if (code !in 200..299) {
                val kind = RouteSelector.classify(code, null, text)
                return Result.Failure(kind, code, apiMessage(text), stages, elapsed(startedAt))
            }

            stageAt = System.nanoTime()
            val content = runCatching {
                JSONObject(text).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
            }.getOrElse {
                addStage(stages, "PARSE", false, elapsed(stageAt), "нет choices[0].message.content")
                return Result.Failure("bad_response", code, apiMessage(text), stages, elapsed(startedAt))
            }

            if (content.isBlank()) {
                addStage(stages, "PARSE", false, elapsed(stageAt), "пустой message.content")
                return Result.Failure("empty_response", code, "пустой текст ответа", stages, elapsed(startedAt))
            }
            addStage(stages, "PARSE", true, elapsed(stageAt), "ответ ${content.length} символов")
            return Result.Success(content, code, stages, elapsed(startedAt))
        } catch (e: Exception) {
            val detail = simple(e)
            addStage(stages, "UNEXPECTED", false, 0L, detail)
            return Result.Failure("network", httpStatus, detail, stages, elapsed(startedAt))
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * Lightweight authenticated check: `GET /models`.
     *
     * It proves the key is accepted and the provider is reachable right now, without
     * spending completion tokens. The stage list is reused by the diagnostics screen.
     */
    fun probe(
        apiKey: String,
        modelId: String = Route.MODEL_FREE,
        baseUrl: String = Route.ROUTEWAY_BASE_URL
    ): Result {
        val stages = JSONArray()
        val startedAt = System.nanoTime()
        var connection: HttpURLConnection? = null
        var httpStatus: Int? = null
        try {
            val endpoint = runCatching {
                URL(baseUrl.trimEnd('/') + "/models")
            }.getOrElse {
                addStage(stages, "CONFIG", false, 0L, "неверный адрес: $baseUrl")
                return Result.Failure("config", null, "неверный адрес: $baseUrl", stages, elapsed(startedAt))
            }
            val host = endpoint.host

            if (apiKey.isBlank()) {
                addStage(stages, "KEY", false, 0L, "ключ Routeway не задан")
                return Result.Failure("no_key", null, "ключ не задан", stages, elapsed(startedAt))
            }
            addStage(stages, "KEY", true, 0L, "ключ присутствует (${apiKey.length} символов)")

            var stageAt = System.nanoTime()
            val addresses = try {
                InetAddress.getAllByName(host)
            } catch (e: Exception) {
                val detail = simple(e)
                addStage(stages, "DNS", false, elapsed(stageAt), "не удалось разрешить $host · $detail")
                return Result.Failure("network", null, "DNS не разрешил $host · $detail", stages, elapsed(startedAt))
            }
            addStage(
                stages, "DNS", true, elapsed(stageAt),
                "$host → " + addresses.joinToString(", ") { it.hostAddress.orEmpty() }.take(200)
            )



            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }

            stageAt = System.nanoTime()
            try {
                connection.connect()
            } catch (e: Exception) {
                val kind = if (e is SocketTimeoutException) "timeout" else "network"
                val detail = simple(e)
                addStage(stages, "CONNECT", false, elapsed(stageAt), "TCP/TLS не установлены · $detail")
                return Result.Failure(kind, null, detail, stages, elapsed(startedAt))
            }
            addStage(stages, "CONNECT", true, elapsed(stageAt), "TCP+TLS установлены")

            stageAt = System.nanoTime()
            httpStatus = try {
                connection.responseCode
            } catch (e: Exception) {
                val kind = if (e is SocketTimeoutException) "timeout" else "network"
                val detail = simple(e)
                addStage(stages, "HTTP", false, elapsed(stageAt), "ответ не получен · $detail")
                return Result.Failure(kind, null, detail, stages, elapsed(startedAt))
            }
            addStage(
                stages, "HTTP", httpStatus in 200..299, elapsed(stageAt),
                "HTTP $httpStatus " + runCatching { connection.responseMessage.orEmpty() }.getOrDefault("")
            )

            stageAt = System.nanoTime()
            val text = runCatching {
                val stream = if (httpStatus in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            addStage(stages, "READ", true, elapsed(stageAt), "${text.length} символов")

            val code = httpStatus ?: 0
            if (code !in 200..299) {
                val kind = RouteSelector.classify(code, null, text)
                // Some gateways do not expose /models. A 404 there says nothing about the
                // route, so fall back to the cheapest possible completion before judging.
                if (code == 404) {
                    val fallback = completionProbe(apiKey, modelId, baseUrl, stages, startedAt)
                    if (fallback != null) return fallback
                }
                return Result.Failure(kind, code, apiMessage(text), stages, elapsed(startedAt))
            }
            return Result.Success("", code, stages, elapsed(startedAt))
        } catch (e: Exception) {
            val detail = simple(e)
            addStage(stages, "UNEXPECTED", false, 0L, detail)
            return Result.Failure("network", httpStatus, detail, stages, elapsed(startedAt))
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * Cheapest possible authenticated completion, used when `/models` is not exposed.
     * Returns null when the probe itself could not be attempted, so the caller keeps the
     * original verdict.
     */
    private fun completionProbe(
        apiKey: String,
        modelId: String,
        baseUrl: String,
        stages: JSONArray,
        startedAt: Long
    ): Result? {
        var connection: HttpURLConnection? = null
        return try {
            val endpoint = URL(baseUrl.trimEnd('/') + "/chat/completions")
            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val body = JSONObject()
                .put("model", modelId)
                .put("messages", JSONArray().put(JSONObject()
                    .put("role", "user").put("content", "ping")))
                .put("max_tokens", 1)
            val writeAt = System.nanoTime()
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            addStage(stages, "FALLBACK_WRITE", true, elapsed(writeAt), "проверка через /chat/completions")
            val code = connection.responseCode
            addStage(stages, "FALLBACK_HTTP", code in 200..299, 0L, "HTTP $code")
            val text = runCatching {
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            if (code in 200..299) {
                Result.Success("", code, stages, elapsed(startedAt))
            } else {
                Result.Failure(
                    RouteSelector.classify(code, null, text), code, apiMessage(text),
                    stages, elapsed(startedAt)
                )
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** Human-readable provider message from an error body. */
    fun apiMessage(text: String): String {
        if (text.isBlank()) return ""
        val extracted = runCatching {
            val json = JSONObject(text)
            val error = json.optJSONObject("error")
            when {
                error != null -> error.optString("message").ifBlank { error.toString() }
                json.has("message") -> json.optString("message")
                json.has("detail") -> json.optString("detail")
                else -> text
            }
        }.getOrElse { text }
        return SecretRedactor.redactText(extracted).replace(Regex("\\s+"), " ").trim().take(300)
    }

    /** True when the request never left the device, so an automatic retry is pointless. */
    fun neverSent(stages: JSONArray): Boolean = stages.length() == 0

    private fun addStage(stages: JSONArray, name: String, ok: Boolean, ms: Long, detail: String) {
        stages.put(
            JSONObject().put("name", name).put("ok", ok).put("ms", ms)
                .put("detail", SecretRedactor.redactText(detail).take(240))
        )
    }

    private fun simple(e: Exception): String = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"

    private fun elapsed(from: Long): Long = (System.nanoTime() - from) / 1_000_000L
}
