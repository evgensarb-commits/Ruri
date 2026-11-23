package com.ruri

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Vision {
    private const val TINY_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    /** Select only routes accepted by the shared free-route policy; no paid or disabled fallback. */
    fun visionRoute(root: File): JSONObject? {
        val config = runCatching { JSONObject(SecretStore.readRuntimeConfig(root)) }.getOrElse { JSONObject() }
        val models = config.optJSONArray("models") ?: JSONArray()
        return findVisionRoute(
            root = root,
            models = models,
            current = config.optInt("current", 0),
            online = NetState.online,
            vpn = NetState.vpn,
            probeRoute = ::probe
        )
    }

    /** Returns the first enabled free route with a manually declared vision capability. */
    fun cachedVisionRoute(root: File): JSONObject? {
        val config = runCatching { JSONObject(SecretStore.readRuntimeConfig(root)) }.getOrElse { JSONObject() }
        val models = config.optJSONArray("models") ?: JSONArray()
        val order = RouteSelector.orderedIndices(
            root, models, config.optInt("current", 0), Task.VISION
        )
        return order.firstNotNullOfOrNull { index ->
            models.optJSONObject(index)?.takeIf(::isEligibleVisionModel)?.let { JSONObject(it.toString()) }
        }
    }

    /** Vision capability is a manual route declaration; no provider probe or auto-capability flag. */
    internal fun findVisionRoute(
        root: File,
        models: JSONArray,
        current: Int,
        online: Boolean,
        vpn: Boolean,
        probeRoute: (JSONObject) -> Boolean,
        now: Long = System.currentTimeMillis()
    ): JSONObject? {
        val order = RouteSelector.orderedIndices(
            root = root,
            models = models,
            current = current,
            task = Task.VISION,
            online = online,
            vpn = vpn
        )
        return order.firstNotNullOfOrNull { index ->
            models.optJSONObject(index)?.takeIf(::isEligibleVisionModel)?.let { JSONObject(it.toString()) }
        }
    }

    internal fun isEligibleVisionModel(model: JSONObject): Boolean =
        model.optBoolean("free_tier", false) &&
            model.optBoolean("enabled", true) &&
            routeId(model).isNotBlank() &&
            model.optString("api_key").isNotBlank() &&
            model.optString("base_url").startsWith("https://", ignoreCase = true)

    private fun routeId(model: JSONObject): String = model.optString("id").ifBlank {
        model.optString("name")
    }

    internal fun isUsableProbeResponse(response: String?): Boolean {
        val value = response?.trim().orEmpty()
        return value.isNotBlank() &&
            !value.contains("не могу", ignoreCase = true) &&
            !value.contains("cannot", ignoreCase = true)
    }

    private fun probe(model: JSONObject): Boolean {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Какой цвет у пикселя? Ответь одним словом."))
            .put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/png;base64," + TINY_PNG)))))
        return isUsableProbeResponse(call(model, messages))
    }

    fun describe(root: File, bytes: ByteArray, mime: String, prompt: String): String? {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES || !mime.startsWith("image/", ignoreCase = true)) return null
        val model = visionRoute(root) ?: return null
        val id = routeId(model)
        RouteMeta.recordAttempt(root, id)
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:" + mime + ";base64," + base64)))
        val response = call(model, JSONArray().put(JSONObject().put("role", "user").put("content", content)), root, id)
        val description = response?.let { SecretRedactor.redactText(it).take(4000).takeIf { text -> text.isNotBlank() } }
        if (description.isNullOrBlank()) return null
        runCatching { RouteMeta.recordSuccess(root, id, Task.VISION) }
        return description
    }

    fun describeShelfFile(root: File, name: String): String? {
        val safe = ShelfWatcher.safeName(name) ?: return null
        val file = File(root, "shelf/" + safe)
        if (!file.isFile || file.length() > MAX_IMAGE_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val mime = mimeOf(bytes) ?: return null
        return describe(root, bytes, mime,
            "Опиши изображение с полки подробно: объекты, люди и их позы, текст на картинке, стиль и настроение. Не выдумывай детали.")
    }

    fun mimeOf(bytes: ByteArray): String? = when {
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size > 12 && String(bytes.sliceArray(0..3), Charsets.US_ASCII) == "RIFF" &&
            String(bytes.sliceArray(8..11), Charsets.US_ASCII) == "WEBP" -> "image/webp"
        bytes.size > 6 && (String(bytes.sliceArray(0..5), Charsets.US_ASCII) == "GIF87a" ||
            String(bytes.sliceArray(0..5), Charsets.US_ASCII) == "GIF89a") -> "image/gif"
        else -> null
    }

    private fun call(model: JSONObject, messages: JSONArray, root: File? = null, routeId: String = ""): String? {
        var connection: HttpURLConnection? = null
        return try {
            val base = model.optString("base_url").trimEnd('/')
            if (!base.startsWith("https://")) return null
            connection = (URL(base + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 18000
                readTimeout = 35000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                val key = model.optString("api_key")
                if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer " + key)
            }
            val body = JSONObject().put("model", model.optString("model"))
                .put("messages", messages).put("temperature", 0.3).put("max_tokens", 600)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (root != null && routeId.isNotBlank()) RouteMeta.recordHttpStatus(root, routeId, code)
            if (code !in 200..299) {
                if (root != null && routeId.isNotBlank()) {
                    RouteMeta.recordFailure(root, routeId, RouteSelector.classify(code, null), Task.VISION)
                }
                null
            } else {
                val content = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                    .getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    .getString("content").trim()
                if (content.isBlank()) {
                    if (root != null && routeId.isNotBlank()) RouteMeta.recordFailure(root, routeId, "empty_response", Task.VISION)
                    null
                } else content
            }
        } catch (e: Exception) {
            if (root != null && routeId.isNotBlank()) {
                RouteMeta.recordFailure(root, routeId, RouteSelector.classify(null, e), Task.VISION)
            }
            null
        } finally {
            connection?.disconnect()
        }
    }
}
