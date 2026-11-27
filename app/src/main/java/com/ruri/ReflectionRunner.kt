package com.ruri

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs [ReflectionPass] against a real model.
 *
 * The separation matters: the pass enforces the budget and the provenance rule, this object only
 * supplies meaning. That way a future model change cannot quietly remove the limits.
 */
object ReflectionRunner {

    private const val DUE_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val STATE_KEY = "last_reflection_at"

    /** True when enough time passed and the journal has events she has not read yet. */
    fun isDue(root: java.io.File, now: Long = System.currentTimeMillis()): Boolean {
        val last = runCatching {
            JSONObject(FileManager.read(root, "memory/L4_state.json")).optLong(STATE_KEY, 0L)
        }.getOrDefault(0L)
        if (last > 0L && now - last < DUE_INTERVAL_MS) return false
        // Nothing new to read means nothing to do; this avoids a pointless model call.
        return ReflectionPass.cursor(root) < RawLog.sizeBytes(root)
    }

    fun runIfDue(context: Context) {
        val root = context.filesDir
        if (!isDue(root)) return
        run(context, root)
    }

    /**
     * One bounded pass. Returns null when the model could not be reached — the cursor is not
     * advanced in that case, so the same window is retried later instead of being skipped.
     */
    fun run(context: Context, root: java.io.File): ReflectionPass.PassResult? {
        val config = runCatching { JSONObject(SecretStore.readRuntimeConfig(root)) }.getOrNull()
            ?: return null
        val models = config.optJSONArray("models") ?: return null
        val apiKey = Route.apiKey(root)
        if (apiKey.isBlank()) return null

        var lastResult: ReflectionPass.PassResult? = null
        ReflectionPass.run(root) { window ->
            val prompt = buildPrompt(window)
            val raw = request(root, apiKey, prompt) ?: return@run emptyList()
            lastResult = null
            parseCandidates(raw)
        }
        runCatching {
            FileManager.updateL4State(root) { state ->
                state.put(STATE_KEY, System.currentTimeMillis())
            }
        }
        return lastResult
    }

    private fun buildPrompt(window: List<RawLog.Entry>): String {
        val journal = StringBuilder()
        for (entry in window) journal.append(RawLog.describe(entry, 260)).append('\n')
        return """
            Ты читаешь свой собственный сырой журнал и выводишь из него устойчивые утверждения.

            ЖУРНАЛ (это факты о том, что произошло):
            $journal

            ЗАДАЧА. Найди то, что стоит запомнить надолго: устойчивые предпочтения Командира,
            повторяющиеся привычки, важные события, изменения в отношениях. Не пересказывай
            журнал и не выдумывай того, чего в нём нет.

            ФОРМАТ. Верни ровно JSON-объект, без текста вокруг:
            {"candidates":[{"text":"...","category":"...","source_ids":["id1","id2"],"confidence":0.7}]}

            ПРАВИЛА, НАРУШАТЬ НЕЛЬЗЯ:
            - В source_ids указывай ТОЛЬКО идентификаторы из журнала выше, те записи, на которых
              реально основано утверждение. Придуманный идентификатор будет отброшен, и вывод
              сохранится как предположение, а не как факт.
            - Если оснований нет — не возвращай кандидата вообще.
            - Одно утверждение — одна мысль, до 300 символов.
            - Уже известное повторно не выводи: дубли будут схлопнуты.
        """.trimIndent()
    }

    private fun request(root: java.io.File, apiKey: String, prompt: String): String? {
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", prompt)
        )
        return when (val result = Transport.chat(
            messages = messages,
            apiKey = apiKey,
            maxTokens = 900,
            modelId = Route.model(root),
            baseUrl = Route.baseUrl(root)
        )) {
            is Transport.Result.Success -> result.reply
            is Transport.Result.Failure -> {
                Log.w("Ruri", "Reflection model call failed: ${result.kind}")
                null
            }
        }
    }

    /** Accepts a fenced or bare object; anything unparsable yields no candidates rather than noise. */
    internal fun parseCandidates(raw: String): List<JSONObject> {
        val trimmed = raw.trim()
        val fenced = Regex("(?is)```(?:json)?\\s*(\\{.*\\})\\s*```").find(trimmed)
        val jsonText = fenced?.groupValues?.get(1)
            ?: trimmed.takeIf { it.startsWith("{") && it.endsWith("}") }
            ?: return emptyList()
        val parsed = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val array = parsed.optJSONArray("candidates") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("text").trim().length < 5) continue
            out.add(item)
        }
        return out
    }
}
