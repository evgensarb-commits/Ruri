package com.ruri

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Image understanding through the active provider.
 *
 * Why the old implementation never worked: it required a route flag `vision: true` that the
 * configuration no longer carries, so `visionRoute()` returned null every time and the caller
 * silently got "vision unavailable". It also wrote nothing to diagnostics, so the failure was
 * invisible.
 *
 * This version does not trust a config flag. It sends one tiny test image, remembers the
 * provider's verdict, and reports the real reason when images are not supported.
 */
object Vision {
    /** 1x1 transparent PNG: enough to ask "can you see images at all". */
    private const val TINY_PNG =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_DESCRIPTION = 4000
    private const val STATE_PATH = "memory/vision_state.json"

    private const val KEY_SUPPORTED = "supported"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_REASON = "reason"

    /** Serialises probes so a burst of images does not fire several test requests. */
    private val probeLock = Any()

    // ------------------------------------------------------------------ capability

    /**
     * Tri-state: true = provider accepted an image, false = refused, null = not checked yet.
     */
    fun supportState(root: File): Boolean? {
        val state = runCatching { JSONObject(FileManager.read(root, STATE_PATH)) }.getOrNull()
            ?: return null
        if (!state.has(KEY_SUPPORTED)) return null
        return state.optBoolean(KEY_SUPPORTED, false)
    }

    fun lastReason(root: File): String =
        runCatching { JSONObject(FileManager.read(root, STATE_PATH)).optString(KEY_REASON) }
            .getOrDefault("")

    private fun remember(root: File, supported: Boolean, reason: String) {
        runCatching {
            FileManager.write(root, STATE_PATH, JSONObject()
                .put(KEY_SUPPORTED, supported)
                .put(KEY_CHECKED_AT, System.currentTimeMillis())
                .put(KEY_REASON, SecretRedactor.redactText(reason).take(300))
                .toString(2))
        }
        FileManager.appendEvent(root, JSONObject()
            .put("type", "vision_probe_result")
            .put("capable", supported)
            .put("reason", SecretRedactor.redactText(reason).take(300))
            .put("ts", System.currentTimeMillis()))
    }

    /**
     * Sends a 1x1 image and records whether the provider accepted it.
     *
     * Some models answer with prose instead of a refusal; that still counts as support, because
     * the request itself succeeded and the image was in the payload.
     */
    fun probe(root: File, force: Boolean = false): Pair<Boolean, String> {
        synchronized(probeLock) {
            if (!force) {
                supportState(root)?.let { supported ->
                    return supported to lastReason(root)
                }
            }
            val apiKey = Route.apiKey(root)
            if (apiKey.isBlank()) {
                val reason = "Ключ провайдера не задан, изображения проверить нельзя."
                remember(root, false, reason)
                return false to reason
            }
            val messages = JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "Опиши одно слово: что на картинке?"))
                    .put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/png;base64," + TINY_PNG)))))
            return when (val result = Transport.chat(messages, apiKey, 40, Route.model(root))) {
                is Transport.Result.Success -> {
                    remember(root, true, "Провайдер принял тестовое изображение.")
                    true to "Провайдер принял тестовое изображение."
                }
                is Transport.Result.Failure -> {
                    // A rejected image is a capability limit, not a broken route: keep the
                    // wording specific so the UI can explain it.
                    val reason = describeRefusal(result.kind, result.httpStatus, result.detail)
                    remember(root, false, reason)
                    false to reason
                }
            }
        }
    }

    private fun describeRefusal(kind: String, status: Int?, detail: String): String = when {
        status == 400 || status == 404 || status == 415 || status == 422 ->
            "Модель не принимает изображения: провайдер отклонил запрос с картинкой (HTTP $status). " +
                "Выбранная модель текстовая — для картинок нужна мультимодальная модель."
        kind == "no_key" -> "Ключ провайдера не задан."
        else -> "Не удалось проверить изображения: ${RetryPolicy.label(kind)}" +
            (if (detail.isBlank()) "" else " · $detail")
    }

    // ------------------------------------------------------------------ description

    /**
     * One image, one description. Returns null when it cannot be described; the reason is
     * available via [explainFailure] so callers can tell the Commander what went wrong.
     */
    fun describe(root: File, bytes: ByteArray, mime: String, prompt: String): String? =
        describeDetailed(root, bytes, mime, prompt).first

    /** Returns (description or null, reason when null). */
    fun describeDetailed(root: File, bytes: ByteArray, mime: String, prompt: String): Pair<String?, String> {
        if (bytes.isEmpty()) return null to "Изображение пустое."
        if (bytes.size > MAX_IMAGE_BYTES) return null to "Изображение больше 8 МБ."
        if (!mime.startsWith("image/", ignoreCase = true)) return null to "Это не изображение."

        // A cached refusal is checked first: if the provider already said "no images", there is
        // no point in repeating the request, and the reason is more specific than "no key".
        val known = supportState(root)
        if (known == false) return null to lastReason(root)

        val apiKey = Route.apiKey(root)
        if (apiKey.isBlank()) return null to "Ключ провайдера не задан."

        val messages = JSONArray().put(JSONObject()
            .put("role", "user")
            .put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                    .put("url", "data:" + mime + ";base64," +
                        Base64.encodeToString(bytes, Base64.NO_WRAP))))))

        return when (val result = Transport.chat(messages, apiKey, 700, Route.model(root))) {
            is Transport.Result.Success -> {
                if (supportState(root) == null) remember(root, true, "Провайдер принял изображение.")
                val safe = SecretRedactor.redactText(result.reply).take(MAX_DESCRIPTION)
                if (safe.isBlank()) null to "Модель не описала изображение." else safe to ""
            }
            is Transport.Result.Failure -> {
                val reason = describeRefusal(result.kind, result.httpStatus, result.detail)
                // Only a real refusal is remembered; a network wobble must not disable vision.
                if (result.httpStatus == 400 || result.httpStatus == 415 || result.httpStatus == 422) {
                    remember(root, false, reason)
                }
                null to reason
            }
        }
    }

    /** Sniffing by magic bytes; the extension is never trusted. */
    fun mimeOf(bytes: ByteArray): String? = when {
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size > 12 && String(bytes.sliceArray(0..3), Charsets.US_ASCII) == "RIFF" &&
            String(bytes.sliceArray(8..11), Charsets.US_ASCII) == "WEBP" -> "image/webp"
        // "GIF87a"/"GIF89a" are exactly 6 bytes, so the guard must be >= 6.
        bytes.size >= 6 && (String(bytes.sliceArray(0..5), Charsets.US_ASCII) == "GIF87a" ||
            String(bytes.sliceArray(0..5), Charsets.US_ASCII) == "GIF89a") -> "image/gif"
        else -> null
    }

    fun isImage(bytes: ByteArray): Boolean = mimeOf(bytes) != null

    /** Human-facing status line for the settings screen. */
    fun statusLine(root: File): String = when (supportState(root)) {
        true -> "Изображения: поддерживаются."
        false -> "Изображения: " + lastReason(root).ifBlank { "модель их не принимает." }
        null -> "Изображения: ещё не проверялись."
    }
}
