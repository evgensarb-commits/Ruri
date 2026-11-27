package com.ruri

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Incremental fact extraction. A durable message window and L0 byte cursor are
 * checkpointed locally, so process death or a temporarily unavailable model
 * does not force a full-history scan or discard accumulated context.
 */
object FactExtractor {
    private const val STATE_PATH = "memory/L4_state.json"
    private const val FACTS_PATH = "memory/L1_facts.json"
    private const val PENDING_PATH = "memory/fact_extraction_pending.json"
    private const val MIN_COMMANDER_MESSAGES = 3
    private const val MAX_WINDOW_MESSAGES = 32
    private const val RETRY_COOLDOWN_MS = 30L * 60L * 1000L
    private val processLock = Any()

    internal data class ApplyResult(val added: Int, val updated: Int)

    fun extractIfDue(context: Context): Int = synchronized(processLock) {
        extractLocked(context)
    }

    private fun extractLocked(context: Context): Int {
        val root = context.filesDir
        val state = readStateStrict(root)
        val now = System.currentTimeMillis()
        if (now - state.optLong("last_fact_extraction_attempt_at", 0L) < RETRY_COOLDOWN_MS) return 0

        val pending = readOrInitializePending(root, state)
        val scanOffset = pending.optLong("scan_offset", 0L).coerceAtLeast(0L)
        val batch = FileManager.readL0Batch(root, scanOffset)
        val transcript = pending.optJSONArray("transcript_messages") ?: JSONArray()
        val commanderMessages = pending.optJSONArray("commander_messages") ?: JSONArray()

        for (event in batch.events) {
            if (event.optString("type") != "message" || event.optString("text").isBlank()) continue
            if (event.optString("id").isBlank()) continue
            transcript.put(JSONObject(event.toString()))
            if (event.optString("who") == "commander") commanderMessages.put(JSONObject(event.toString()))
        }
        trimTail(transcript, MAX_WINDOW_MESSAGES)
        trimTail(commanderMessages, MAX_WINDOW_MESSAGES)

        pending.put("initialized", true)
        pending.put("scan_offset", batch.nextOffset)
        pending.put("transcript_messages", transcript)
        pending.put("commander_messages", commanderMessages)
        pending.put("last_scan_at", now)
        pending.put("l0_backlog", batch.hasMore)
        FileManager.write(root, PENDING_PATH, pending.toString(2))

        val commanderIds = (0 until commanderMessages.length()).mapNotNull { index ->
            commanderMessages.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }
        }.toSet()
        if (commanderIds.size < MIN_COMMANDER_MESSAGES) return 0

        val latestCommander = commanderMessages.optJSONObject(commanderMessages.length() - 1)
            ?: return 0
        val batchId = "facts:${pending.optLong("start_offset", 0L)}:${batch.nextOffset}:${latestCommander.optString("id")}"

        val factsRaw = FileManager.read(root, FACTS_PATH)
        val factsFile = try {
            if (factsRaw.isBlank()) throw IllegalStateException("L1 file is empty")
            JSONObject(factsRaw)
        } catch (e: Exception) {
            preserveCorrupt(root, FACTS_PATH, factsRaw)
            recordAttempt(root, state, now)
            Log.w("Ruri", "Automatic fact extraction stopped: L1 is malformed and was preserved", e)
            return 0
        }
        val facts = factsFile.optJSONArray("facts") ?: run {
            preserveCorrupt(root, FACTS_PATH, factsRaw)
            recordAttempt(root, state, now)
            Log.w("Ruri", "Automatic fact extraction stopped: L1 has no facts array; original was preserved")
            return 0
        }

        // L1 may have committed just before process death. Finish the matching cursor checkpoint.
        if (factsFile.optString("last_extraction_batch_id") == batchId) {
            finishSuccessfulBatch(root, state, pending, latestCommander, batch.nextOffset, batchId, "", now)
            return 0
        }

        val transcriptWindow = JSONArray()
        for (index in 0 until transcript.length()) {
            val event = transcript.optJSONObject(index) ?: continue
            transcriptWindow.put(
                JSONObject()
                    .put("event_id", event.optString("id"))
                    .put("speaker", event.optString("who"))
                    .put("text", SecretRedactor.redactText(event.optString("text")).take(1400))
            )
        }

        val config = try {
            JSONObject(SecretStore.readRuntimeConfig(root))
        } catch (e: Exception) {
            Log.w("Ruri", "Fact extraction skipped: model config unavailable", e)
            recordAttempt(root, state, now)
            return 0
        }
        val models = config.optJSONArray("models") ?: JSONArray()
        // One route only: index 0 or nothing.
        val order: List<Int> = if (models.length() > 0) listOf(0) else emptyList()
        val prompt = buildPrompt(transcriptWindow)
        var parsed: JSONObject? = null
        var usedModel = ""
        for (index in order) {
            val model = models.optJSONObject(index) ?: continue
            // Fact extraction requires personal transcript context; never send it to minimal-context routes.
            val key = model.optString("api_key")
            if (key.isBlank()) continue
            val routeId = model.optString("id").ifBlank { model.optString("name", "route_$index") }
            runCatching { RouteMeta.recordAttempt(root, routeId) }
            val response = request(model, prompt, root, routeId)
            if (response.isNullOrBlank()) continue
            parsed = parseJson(response)
            if (parsed != null) {
                runCatching { RouteMeta.recordSuccess(root, routeId, Task.REASON) }
                usedModel = model.optString("name", model.optString("id", "model"))
                break
            } else {
                runCatching { RouteMeta.recordFailure(root, routeId, "other", Task.REASON) }
            }
        }

        if (parsed == null) {
            recordAttempt(root, state, now)
            return 0
        }

        val candidates = parsed.optJSONArray("facts") ?: JSONArray()
        val applied = applyCandidates(facts, candidates, commanderIds, usedModel, now)
        val added = applied.added
        val updated = applied.updated

        factsFile.put("facts", facts)
        factsFile.put("last_extraction_batch_id", batchId)
        factsFile.put("updated_at", now)
        FileManager.write(root, FACTS_PATH, factsFile.toString(2))

        finishSuccessfulBatch(root, state, pending, latestCommander, batch.nextOffset, batchId, usedModel, now)
        FileManager.appendEvent(
            root,
            JSONObject()
                .put("type", "facts_extracted")
                .put("source", "memory")
                .put("batch_id", batchId)
                .put("added", added)
                .put("updated", updated)
                .put("commander_messages", commanderMessages.length())
                .put("model", usedModel)
                .put("privacy", "local")
        )
        return added + updated
    }

    /**
     * Applies model proposals only when they cite real Commander event IDs.
     * This is deterministic and deliberately separate from networking for unit tests.
     */
    internal fun applyCandidates(
        facts: JSONArray,
        candidates: JSONArray,
        commanderIds: Set<String>,
        sourceModel: String,
        now: Long
    ): ApplyResult {
        var added = 0
        var updated = 0
        for (index in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(index) ?: continue
            val text = SecretRedactor.redactText(candidate.optString("text").trim()).take(300)
            if (text.length < 5 || text.contains(SecretRedactor.PLACEHOLDER)) continue

            val confidence = candidate.optDouble("confidence", 0.0)
            if (!confidence.isFinite() || confidence < 0.82 || confidence > 1.0) continue

            val evidence = candidate.optJSONArray("evidence_event_ids") ?: JSONArray()
            val validEvidence = JSONArray()
            val seenEvidence = HashSet<String>()
            for (evidenceIndex in 0 until evidence.length()) {
                val id = evidence.optString(evidenceIndex)
                if (id in commanderIds && seenEvidence.add(id)) validEvidence.put(id)
            }
            val explicit = candidate.optBoolean("explicit", false)
            if (validEvidence.length() == 0) continue
            if (!explicit && validEvidence.length() < 2) continue

            val normalized = normalize(text)
            var existing: JSONObject? = null
            for (factIndex in 0 until facts.length()) {
                val fact = facts.optJSONObject(factIndex) ?: continue
                if (normalize(fact.optString("text")) == normalized) {
                    existing = fact
                    break
                }
            }

            if (existing != null) {
                existing.put("confidence", maxOf(existing.optDouble("confidence", 0.0), confidence))
                existing.put("updated_at", now)
                existing.put("last_confirmed_at", now)
                existing.put("explicit", existing.optBoolean("explicit", false) || explicit)
                existing.put("source_event_ids", mergeIds(existing.optJSONArray("source_event_ids"), validEvidence))
                if (existing.optString("source_model").isBlank()) existing.put("source_model", sourceModel)
                updated++
            } else {
                // A full store no longer silently drops the new claim: the weakest entry goes.
                if (facts.length() >= ReflectionPass.FACT_CAP) {
                    MemoryWeight.makeRoom(
                        root, "L1", facts, ReflectionPass.FACT_CAP,
                        "pending-" + UUID.randomUUID().toString()
                    )
                }
                facts.put(
                    JSONObject()
                        .put("id", "fact-" + UUID.randomUUID().toString())
                        .put("text", text)
                        .put("category", candidate.optString("category", "general").take(40))
                        .put("confidence", confidence)
                        .put("explicit", explicit)
                        .put("source_event_ids", validEvidence)
                        .put("origin", "automatic_extraction")
                        .put("sensitivity", candidate.optString("sensitivity", "personal_context").take(40))
                        .put("created_at", now)
                        .put("updated_at", now)
                        .put("last_confirmed_at", now)
                        .put("source_model", sourceModel)
                )
                added++
            }
        }
        return ApplyResult(added, updated)
    }

    private fun finishSuccessfulBatch(
        root: File,
        state: JSONObject,
        pending: JSONObject,
        latestCommander: JSONObject,
        scanOffset: Long,
        batchId: String,
        model: String,
        now: Long
    ) {
        FileManager.updateL4State(root) { latest ->
            latest.put("last_fact_extraction_attempt_at", now)
                .put("last_fact_extraction_ts", latestCommander.optLong("timestamp", latestCommander.optLong("ts", now)))
                .put("last_fact_extraction_event_id", latestCommander.optString("id"))
                .put("last_fact_extraction_offset", scanOffset)
                .put("last_fact_extraction_batch_id", batchId)
                .put("last_fact_extraction_model", model)
        }

        // The pending transcript is reset only after L1 and L4 checkpoints are committed.
        val reset = JSONObject()
            .put("schema_version", 1)
            .put("initialized", true)
            .put("start_offset", scanOffset)
            .put("scan_offset", scanOffset)
            .put("transcript_messages", JSONArray())
            .put("commander_messages", JSONArray())
            .put("last_completed_at", now)
        FileManager.write(root, PENDING_PATH, reset.toString(2))
    }

    private fun recordAttempt(root: File, state: JSONObject, now: Long) {
        FileManager.updateL4State(root) { latest ->
            latest.put("last_fact_extraction_attempt_at", now)
        }
    }

    private fun readOrInitializePending(root: File, state: JSONObject): JSONObject {
        val raw = FileManager.read(root, PENDING_PATH)
        var pending = if (raw.isBlank()) JSONObject() else try {
            JSONObject(raw)
        } catch (e: Exception) {
            preserveCorrupt(root, PENDING_PATH, raw)
            JSONObject()
        }

        val initialized = pending.optBoolean("initialized", false)
        val structureValid = !initialized ||
            (pending.optJSONArray("transcript_messages") != null &&
                pending.optJSONArray("commander_messages") != null &&
                pending.optLong("scan_offset", -1L) >= 0L &&
                pending.optLong("start_offset", -1L) >= 0L)
        val l0Length = File(root, "memory/L0_raw.jsonl").length()
        val cursorValid = !initialized || pending.optLong("scan_offset", 0L) <= l0Length
        if (!structureValid || !cursorValid) {
            if (raw.isNotBlank()) preserveCorrupt(root, PENDING_PATH, raw)
            pending = JSONObject()
        }

        if (!pending.optBoolean("initialized", false)) {
            val savedOffset = state.optLong("last_fact_extraction_offset", -1L)
            val oldId = state.optString("last_fact_extraction_event_id")
            val migratedOffset = if (savedOffset >= 0L && savedOffset <= l0Length) savedOffset
                else if (oldId.isNotBlank()) FileManager.findL0EventEndOffset(root, oldId) ?: 0L
                else 0L
            val offset = migratedOffset.coerceIn(0L, l0Length)
            pending.put("schema_version", 1)
            pending.put("initialized", true)
            pending.put("start_offset", offset)
            pending.put("scan_offset", offset)
            pending.put("transcript_messages", JSONArray())
            pending.put("commander_messages", JSONArray())
            FileManager.write(root, PENDING_PATH, pending.toString(2))
        }
        return pending
    }

    private fun buildPrompt(transcript: JSONArray): String = """
        Извлеки из переписки устойчивые факты, которые будут полезны Рури в будущих разговорах:
        предпочтения, долгосрочные проекты, явно заявленные цели, повторяющиеся привычки,
        важные события, отношения, личные обстоятельства и значимые особенности Командира.
        Учитывай как доказательства только реплики со speaker="commander". Ответы assistant разрешены как контекст.
        Не выводи предположения из одного случайного сообщения. Личные сведения, включая здоровье,
        убеждения, отношения и местоположение, не исключай автоматически: сохраняй их, если Командир
        сообщил их явно или они устойчиво повторяются и важны для будущего контекста.
        Никогда не извлекай и не сохраняй пароли, коды подтверждения, токены, ключи, номера карт. Личный контекст (предпочтения, проекты, отношения) сохраняй; секреты — нет.
        Для каждого факта укажи точные event_id исходных реплик Командира.
        Верни только корректный JSON без markdown:
        {"facts":[{"text":"...","category":"preference|project|goal|habit|personal_context|other","confidence":0.0,"explicit":true,"sensitivity":"normal|personal_context","evidence_event_ids":["evt-..."]}]}
        Если устойчивых фактов нет, верни {"facts":[]}.
        Переписка:
        $transcript
    """.trimIndent()

    private fun request(model: JSONObject, prompt: String, root: File, routeId: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val baseUrl = model.optString("base_url").trimEnd('/')
            require(baseUrl.startsWith("https://", ignoreCase = true)) { "Provider endpoint must use HTTPS" }
            connection = (URL(baseUrl + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 25000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                val key = model.optString("api_key")
                if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer " + key)
            }
            val body = JSONObject()
                .put("model", model.optString("model"))
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                .put("temperature", 0.1)
                .put("max_tokens", 1200)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            RouteMeta.recordHttpStatus(root, routeId, status)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                RouteMeta.recordFailure(root, routeId, RouteSelector.classify(status, null, raw), Task.REASON)
                return null
            }
            JSONObject(raw).optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            RouteMeta.recordFailure(root, routeId, RouteSelector.classify(null, e), Task.REASON)
            Log.w("Ruri", "Fact extraction provider request failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseJson(raw: String): JSONObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
    }

    private fun readStateStrict(root: File): JSONObject {
        val raw = FileManager.read(root, STATE_PATH)
        return try { JSONObject(raw) } catch (e: Exception) {
            preserveCorrupt(root, STATE_PATH, raw)
            throw IllegalStateException("L4 state is invalid; source file was preserved", e)
        }
    }

    private fun mergeIds(old: JSONArray?, next: JSONArray): JSONArray {
        val result = JSONArray()
        val seen = HashSet<String>()
        old?.let {
            for (index in 0 until it.length()) {
                val value = it.optString(index)
                if (value.isNotBlank() && seen.add(value)) result.put(value)
            }
        }
        for (index in 0 until next.length()) {
            val value = next.optString(index)
            if (value.isNotBlank() && seen.add(value)) result.put(value)
        }
        return result
    }

    private fun trimTail(array: JSONArray, limit: Int) {
        while (array.length() > limit) array.remove(0)
    }

    private fun preserveCorrupt(root: File, path: String, raw: String) {
        if (raw.isBlank()) return
        try {
            val safeName = path.replace('/', '_').replace('\\', '_')
            val backup = File(root, "backup/${safeName}_corrupt_${System.currentTimeMillis()}.json")
            backup.parentFile?.mkdirs()
            if (!backup.exists()) backup.writeText(raw, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("Ruri", "Could not preserve corrupt file $path", e)
        }
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}
