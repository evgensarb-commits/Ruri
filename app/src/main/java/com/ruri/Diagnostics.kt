package com.ruri

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Diagnostics are diagnostic: they perform a real request, show every connection stage,
 * and explain in plain language what is wrong with the model and why there is no reply.
 *
 * Provider keys are read only to decide whether one exists; they are never returned.
 */
object Diagnostics {
    private const val LOG_PATH = "memory/network_diagnostics.jsonl"

    /** Snapshot of the current state. No network access, safe to call on every screen open. */
    fun run(ctx: Context): String {
        val root = ctx.filesDir
        NetState.refresh(ctx.applicationContext)
        return snapshot(root, online = NetState.online, vpn = NetState.vpn)
    }

    /**
     * Live diagnosis: one authenticated `GET /models`. This is what answers
     * «почему нет подключения» — it separates DNS, TLS, HTTP status and provider verdict.
     */
    fun runLive(ctx: Context): String {
        val root = ctx.filesDir
        NetState.refresh(ctx.applicationContext)
        val apiKey = Route.apiKey(root)
        val result = Transport.probe(apiKey, Route.model(root), Route.baseUrl(root))
        val record = JSONObject()
            .put("ok", result is Transport.Result.Success)
            .put("network", JSONObject().put("online", NetState.online).put("vpn", NetState.vpn))
            .put("route", routeInfo(root))
            .put("stages", result.stages)
            .put("total_ms", result.totalMs)
            .put("log_tail", logTail(root, 10))
        when (result) {
            is Transport.Result.Success -> {
                record.put("http_status", result.httpStatus)
                    .put("error_kind", "ok")
                    .put("verdict", verdict("ok", result.httpStatus, "", NetState.online, NetState.vpn, apiKey, Route.requiresVpn(root)))
                // A successful probe is worth remembering: it is evidence, not a guess.
                runCatching {
                    NetworkDiagnostics.record(root, JSONObject()
                        .put("timestamp", System.currentTimeMillis())
                        .put("provider", Route.modelLabel(root))
                        .put("model", Route.model(root))
                        .put("base_url", Route.baseUrl(root))
                        .put("vpn", NetState.vpn)
                        .put("online", NetState.online)
                        .put("result", "ok")
                        .put("error_kind", "ok")
                        .put("stage", "PROBE_COMPLETE")
                        .put("http_status", result.httpStatus)
                        .put("failure_message", "проверка вручную: соединение установлено")
                        .put("explanation", RetryPolicy.explain("ok"))
                        .put("stages", result.stages))
                }
            }
            is Transport.Result.Failure -> {
                record.put("http_status", result.httpStatus ?: JSONObject.NULL)
                    .put("error_kind", result.kind)
                    .put("detail", result.detail)
                    .put("verdict", verdict(result.kind, result.httpStatus, result.detail,
                        NetState.online, NetState.vpn, apiKey, Route.requiresVpn(root)))
                runCatching {
                    NetworkDiagnostics.record(root, JSONObject()
                        .put("timestamp", System.currentTimeMillis())
                        .put("provider", Route.modelLabel(root))
                        .put("model", Route.model(root))
                        .put("base_url", Route.baseUrl(root))
                        .put("vpn", NetState.vpn)
                        .put("online", NetState.online)
                        .put("result", result.kind)
                        .put("error_kind", result.kind)
                        .put("stage", "PROBE_" + ((0 until result.stages.length())
                            .lastOrNull()
                            ?.let { result.stages.optJSONObject(it)?.optString("name") } ?: "UNKNOWN"))
                        .put("http_status", result.httpStatus ?: JSONObject.NULL)
                        .put("failure_message", result.detail.take(300))
                        .put("explanation", RetryPolicy.explain(result.kind, result.httpStatus, result.detail))
                        .put("stages", result.stages))
                }
            }
        }
        return record.toString(2)
    }

    /** Log lines for the diagnostics pane, newest last. */
    fun readLog(ctx: Context): String = logTail(ctx.filesDir, 40).toString()

    internal fun logTail(root: File, limit: Int): JSONArray {
        val out = JSONArray()
        val lines = runCatching { File(root, LOG_PATH).readLines(Charsets.UTF_8) }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
            .takeLast(limit.coerceIn(1, 200))
        for (line in lines) {
            val entry = runCatching { JSONObject(line) }.getOrNull() ?: continue
            out.put(formatLogLine(entry))
        }
        return out
    }

    private fun formatLogLine(entry: JSONObject): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date(entry.optLong("timestamp", 0L)))
        val kind = entry.optString("error_kind").ifBlank { entry.optString("result") }
        val status = entry.opt("http_status")?.takeIf { it != JSONObject.NULL }
        val parts = mutableListOf(stamp)
        if (status != null) parts += "HTTP $status"
        parts += (kind.ifBlank { "—" }) + " · " + RetryPolicy.label(kind)
        val detail = entry.optString("failure_message")
        if (detail.isNotBlank()) parts += detail.take(160)
        return parts.joinToString(" · ")
    }

    internal fun snapshot(root: File, online: Boolean, vpn: Boolean): String {
        val result = JSONObject()
            .put("generated_at", System.currentTimeMillis())
            .put("network", JSONObject().put("online", online).put("vpn", vpn))
            .put("route", routeInfo(root))
            .put("queue", CommanderReplyQueue.status(root))
            .put("last_attempt", lastAttempt(root))
            .put("memory", memoryInfo(root))
            .put("backups", backupInfo(root))
            .put("log_tail", logTail(root, 20))
        return result.toString(2)
    }

    private fun routeInfo(root: File): JSONObject = JSONObject()
        .put("id", Route.providerId(root))
        .put("name", Route.modelLabel(root))
        .put("base_url", Route.baseUrl(root))
        .put("model", Route.model(root))
        .put("has_key", Route.hasKey(root))
        .put("needs_vpn", Route.requiresVpn(root))

    private fun lastAttempt(root: File): JSONObject {
        val raw = runCatching { NetworkDiagnostics.latest(root) }.getOrDefault("{}")
        val entry = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        if (!entry.optBoolean("available", false)) return JSONObject()
        val kind = entry.optString("error_kind").ifBlank { entry.optString("result") }
        return JSONObject()
            .put("at", entry.optLong("timestamp", 0L))
            .put("result", entry.optString("result"))
            .put("error_kind", kind)
            .put("http_status", entry.opt("http_status") ?: JSONObject.NULL)
            .put("stage", entry.optString("stage"))
            .put("vpn", entry.optBoolean("vpn", false))
            .put("online", entry.optBoolean("online", true))
            .put("message", entry.optString("failure_message"))
            .put("explanation", entry.optString("explanation").ifBlank { RetryPolicy.explain(kind) })
            .put("stages", entry.optJSONArray("stages") ?: JSONArray())
    }

    private fun memoryInfo(root: File): JSONObject {
        val l0 = File(root, "memory/L0_raw.jsonl")
        return JSONObject()
            .put("l0_size_kb", if (l0.isFile) l0.length() / 1024L else 0L)
            .put("l0_last_append_at", FileManager.lastAppendTimestamp(root))
            .put("l1_facts_count", runCatching {
                JSONObject(FileManager.read(root, "memory/L1_facts.json")).optJSONArray("facts")?.length() ?: 0
            }.getOrDefault(0))
    }

    private fun backupInfo(root: File): JSONObject {
        val status = runCatching { LocalBackupManager.status(root) }.getOrDefault(JSONObject())
        return JSONObject()
            .put("automatic_count", status.optInt("count", 0))
            .put("maximum", status.optInt("maximum", 14))
            .put("latest_file", status.opt("latest_file") ?: JSONObject.NULL)
            .put("latest_at", status.optLong("latest_at", 0L))
    }

    /**
     * The one-sentence answer the Commander actually reads: what is wrong and what to do.
     */
    internal fun verdict(
        kind: String,
        httpStatus: Int?,
        detail: String,
        online: Boolean,
        vpn: Boolean,
        apiKey: String,
        requiresVpn: Boolean = true
    ): String = when {
        !online ->
            "Нет интернета: Android не видит активную сеть. Проверь Wi-Fi/мобильные данные."
        apiKey.isBlank() ->
            "Ключ провайдера не задан. Настройки → Подключение → вставь ключ и сохрани."
        requiresVpn && !vpn && kind != "ok" ->
            "VPN не обнаружен, а Gemma MeroMero работает только через VPN. " +
                RetryPolicy.explain(kind, httpStatus, detail)
        kind == "ok" ->
            "Соединение работает: DNS, TLS и HTTP в порядке, ключ принят провайдером" +
                (if (httpStatus != null) " (HTTP $httpStatus)." else ".")
        else -> RetryPolicy.explain(kind, httpStatus, detail)
    }
}
