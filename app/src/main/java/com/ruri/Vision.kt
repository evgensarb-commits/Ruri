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
    private const val PROBE_TTL_MS = 24L * 60L * 60L * 1000L

    fun visionRoute(root: File): JSONObject? {
        val config = runCatching { JSONObject(SecretStore.readRuntimeConfig(root)) }.getOrElse { JSONObject() }
        val models = config.optJSONArray("models") ?: JSONArray()
        for (i in 0 until models.length()) {
            val model = models.optJSONObject(i) ?: continue
            if (!model.optBoolean("free_tier", true)) continue
            val meta = RouteMeta.route(root, model.optString("id"))
            if (meta.optJSONObject("caps")?.optBoolean("vision", false) == true) return JSONObject(model.toString())
        }
        val now = System.currentTimeMillis()
        for (i in 0 until models.length()) {
            val model = models.optJSONObject(i) ?: continue
            if (!model.optBoolean("free_tier", true)) continue
            val id = model.optString("id")
            val meta = RouteMeta.route(root, id)
            val caps = meta.optJSONObject("caps") ?: JSONObject()
            if (caps.has("vision") && !caps.optBoolean("vision") &&
                now - meta.optLong("vision_probe_at", 0L) < PROBE_TTL_MS) continue
            val capable = probe(model)
            RouteMeta.update(root, id) {
                val updatedCaps = it.optJSONObject("caps") ?: JSONObject()
                updatedCaps.put("vision", capable)
                it.put("caps", updatedCaps).put("vision_probe_at", now)
            }
            if (capable) return JSONObject(model.toString())
        }
        return null
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
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES || !mime.startsWith("image/")) return null
        val model = visionRoute(root) ?: return null
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:" + mime + ";base64," + base64)))
        val response = call(model, JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            ?: return null
        return SecretRedactor.redactText(response).take(4000).takeIf { it.isNotBlank() }
    }

    fun describeShelfFile(root: File, name: String): String? {
        val safe = ShelfWatcher.safeName(name) ?: return null
        val file = File(root, "shelf/" + safe)
        if (!file.isFile || file.length() > MAX_IMAGE_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val mime = mimeOf(bytes) ?: return null
        return describe(root, bytes, mime,
            "Опиши изображение с полки подробно: объекты, люди, текст, стиль, настроение.")
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

    private fun call(model: JSONObject, messages: JSONArray): String? {
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
            if (connection.responseCode !in 200..299) null
            else JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                .getString("content").trim()
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
