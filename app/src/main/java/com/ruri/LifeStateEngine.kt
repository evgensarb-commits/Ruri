package com.ruri

import android.content.Context
import android.os.StatFs
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.min

/**
 * Local RU-R1 life cycle. L0 is consumed incrementally using a durable byte cursor.
 * Cross-file effects are delivered through a small outbox stored in L4; retries are idempotent.
 */
object LifeStateEngine {
    private const val STATE_PATH = "memory/L4_state.json"
    private const val DRIVES_PATH = "core/drives.json"
    private const val L0_PATH = "memory/L0_raw.jsonl"
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val MAX_EVENTS_PER_CYCLE = 2000
    private const val MAX_BYTES_PER_CYCLE = 4 * 1024 * 1024
    private val processLock = Any()

    fun process(context: Context, reason: String): JSONObject = synchronized(processLock) {
        val root = context.filesDir
        FileManager.init(root)
        processLocked(context, root, reason)
    }

    private fun processLocked(context: Context, root: File, reason: String): JSONObject {
        var state = readStateStrict(root)

        // Finish effects left by a previous interrupted process before consuming more L0.
        val previousOutbox = state.optJSONObject("pending_life_batch")
        if (previousOutbox != null) {
            completePendingBatch(root, state, previousOutbox)
            state = readStateStrict(root)
        }

        val now = System.currentTimeMillis()
        val requestedOffset = resolveCursor(root, state)
        val batch = FileManager.readL0Batch(
            root,
            requestedOffset,
            maxRecords = MAX_EVENTS_PER_CYCLE,
            maxBytes = MAX_BYTES_PER_CYCLE
        )
        val newEvents = batch.events

        // A new L0 message is new activity even when two events share the same millisecond.
        if (applyCommanderActivity(state, newEvents)) EmotionEngine.onCommanderReply(root)

        val battery = BatteryLevel.get(context)
        val freeBytes = try { StatFs(root.absolutePath).availableBytes } catch (_: Exception) { -1L }
        val lastSeen = state.optLong("last_seen_commander", 0L)
        EmotionEngine.tick(root, now, lastSeen,
            state.optJSONObject("recent_activity")?.optLong("last_event_at", now) ?: now)
        val quietHours = if (lastSeen <= 0L) 0.0 else
            ((now - lastSeen).coerceAtLeast(0L) / 3_600_000.0)

        val calculatedNeeds = calculateNeeds(lastSeen, now)
        val needs = state.optJSONObject("needs") ?: JSONObject()
        needs.put("live", calculatedNeeds.optDouble("live"))
        needs.put("love_commander", calculatedNeeds.optDouble("love_commander"))
        needs.put("updated_at", now)
        state.put("needs", needs)

        val device = try {
            DeviceSensors.snapshot(context, now)
        } catch (e: Exception) {
            Log.w("Ruri", "Device snapshot unavailable; retaining a minimal local snapshot", e)
            JSONObject().put("time_unix_ms", now).put("privacy", "local")
        }
        device.put("battery_percent", battery)
        device.put("available_storage_bytes", freeBytes)
        device.put("sampled_at", now)
        state.put("device", device)

        val typeCounts = JSONObject()
        newEvents.forEach { event ->
            val type = event.optString("type", "unknown").ifBlank { "unknown" }
            typeCounts.put(type, typeCounts.optInt(type, 0) + 1)
            if (type == "shelf_file_ingested") EmotionEngine.onShelfFile(root)
        }
        val lastNewEvent = newEvents.lastOrNull()
        state.put(
            "recent_activity",
            JSONObject()
                .put("processed_event_count", newEvents.size)
                .put("processed_record_count", batch.recordsRead)
                .put("malformed_l0_lines", batch.malformedLines)
                .put("event_types", typeCounts)
                .put(
                    "last_event_at",
                    lastNewEvent?.optLong("timestamp", lastNewEvent.optLong("ts", 0L))
                        ?: state.optLong("last_processed_event_ts", 0L)
                )
                .put("cursor_start_offset", batch.startOffset)
                .put("cursor_end_offset", batch.nextOffset)
                .put("updated_at", now)
        )
        state.put("l0_backlog", batch.hasMore)
        state.put("last_local_cycle", now)
        state.put("last_local_reason", reason.take(180))
        state.put("last_quiet_hours", quietHours)
        if (batch.startOffset != requestedOffset) {
            state.put("cursor_recovered_at", now)
            state.put("cursor_recovery_reason", "offset_out_of_range_or_mid_record")
        }

        if (batch.recordsRead > 0) {
            val lastEvent = newEvents.lastOrNull()
            val batchId = "${batch.startOffset}:${batch.nextOffset}:${lastEvent?.optString("id").orEmpty()}"
            val outbox = JSONObject()
                .put("batch_id", batchId)
                .put("start_offset", batch.startOffset)
                .put("end_offset", batch.nextOffset)
                .put("record_count", batch.recordsRead)
                .put("event_count", newEvents.size)
                .put("malformed_count", batch.malformedLines)
                .put("event_types", typeCounts)
                .put("battery_percent", battery)
                .put("processed_at", now)
                .put("last_event_id", lastEvent?.optString("id").orEmpty())
                .put("last_event_ts", lastEvent?.optLong("timestamp", lastEvent.optLong("ts", 0L)) ?: 0L)
            state.put("pending_life_batch", outbox)
            FileManager.write(root, STATE_PATH, state.toString(2))
            completePendingBatch(root, state, outbox)
            state = readStateStrict(root)
        } else {
            // Resource-related drives still change on a quiet cycle, but no activity is invented.
            updateDrives(root, 0, now, "cycle:${now}")
            state.put("last_processed_event_offset", batch.nextOffset)
            FileManager.write(root, STATE_PATH, state.toString(2))
        }

        if (now - state.optLong("last_memory_audit", 0L) >= DAY_MS) {
            val report = auditMemory(root, now)
            state.put("memory_health", report)
            state.put("last_memory_audit", now)
            FileManager.write(root, STATE_PATH, state.toString(2))
        }
        updateSelfModel(root, state, reason, now)
        return state
    }

    internal fun calculateNeeds(lastSeenCommander: Long, now: Long): JSONObject {
        val quietHours = if (lastSeenCommander <= 0L) 0.0 else
            ((now - lastSeenCommander).coerceAtLeast(0L) / 3_600_000.0)
        return JSONObject()
            .put("live", 0.38)
            .put("love_commander", min(0.95, 0.35 + (quietHours / 18.0) * 0.5))
            .put("updated_at", now)
    }

    internal fun applyCommanderActivity(state: JSONObject, newEvents: List<JSONObject>): Boolean {
        val latest = newEvents.lastOrNull {
            it.optString("type") == "message" && it.optString("who") == "commander"
        } ?: return false
        state.put("last_commander_message_event_id", latest.optString("id"))
        state.put("waiting_for_reply", false)
        state.put("ignored_count", 0)
        state.put("offended_until", 0L)
        return true
    }

    internal fun updateSelfModel(root: File, state: JSONObject, reason: String, now: Long) {
        val selfModel = readJsonOptional(root, "memory/self_model.json") ?: return
        if (!selfModel.has("identity")) selfModel.put("identity", "Рури")
        if (!selfModel.has("project")) selfModel.put("project", "RU-R1")
        if (!selfModel.has("primary_needs")) {
            selfModel.put("primary_needs", JSONArray().put("live").put("love_commander"))
        }
        if (!selfModel.has("known_capabilities")) selfModel.put("known_capabilities", JSONArray())
        if (!selfModel.has("limitations")) selfModel.put("limitations", JSONArray())

        val currentState = JSONObject()
            .put("mood", state.optString("mood", "собранная"))
            .put("energy", state.optInt("energy", 5))
            .put("needs", state.optJSONObject("needs")?.let { JSONObject(it.toString()) } ?: JSONObject())
            .put("last_seen_commander", state.optLong("last_seen_commander", 0L))
            .put("waiting_for_reply", state.optBoolean("waiting_for_reply", false))
            .put("ignored_count", state.optInt("ignored_count", 0))
            .put("last_processed_event_offset", state.optLong("last_processed_event_offset", 0L))
            .put("active_goals", state.optJSONArray("active_goals")?.let { JSONArray(it.toString()) } ?: JSONArray())
            .put("active_tasks", state.optJSONArray("active_tasks")?.let { JSONArray(it.toString()) } ?: JSONArray())

        selfModel.put("current_focus", state.optString("current_focus", selfModel.optString("current_focus")))
        selfModel.put("current_state", currentState)
        selfModel.put("last_local_cycle_at", now)
        selfModel.put("last_local_reason", reason.take(180))
        selfModel.put("updated_at", now)
        FileManager.write(root, "memory/self_model.json", selfModel.toString(2))
    }

    private fun resolveCursor(root: File, state: JSONObject): Long {
        if (state.has("last_processed_event_offset")) {
            return state.optLong("last_processed_event_offset", 0L).coerceAtLeast(0L)
        }

        // Migrate the previous ID-based cursor without replaying already processed events.
        val oldId = state.optString("last_processed_event_id")
        if (oldId.isNotBlank()) {
            val oldOffset = FileManager.findL0EventEndOffset(root, oldId)
            if (oldOffset != null) {
                state.put("last_processed_event_offset", oldOffset)
                FileManager.write(root, STATE_PATH, state.toString(2))
                return oldOffset
            }
        }

        // Replaying L0 is safer than dropping equal-timestamp events when the old ID is absent.
        state.put("last_processed_event_offset", 0L)
        if (state.optLong("last_processed_event_ts", 0L) > 0L) {
            state.put("cursor_recovery_reason", "legacy_cursor_not_found_replay_from_start")
            state.put("cursor_recovered_at", System.currentTimeMillis())
        }
        FileManager.write(root, STATE_PATH, state.toString(2))
        return 0L
    }

    private fun completePendingBatch(root: File, state: JSONObject, outbox: JSONObject) {
        val batchId = outbox.optString("batch_id")
        val processedAt = outbox.optLong("processed_at", System.currentTimeMillis())
        val eventCount = outbox.optInt("event_count", 0)
        val recordCount = outbox.optInt("record_count", eventCount)
        val types = outbox.optJSONObject("event_types") ?: JSONObject()
        val endOffset = outbox.optLong("end_offset", 0L)

        updateDrives(root, eventCount, processedAt, batchId)
        state.put("last_processed_event_offset", endOffset)
        val lastId = outbox.optString("last_event_id")
        if (lastId.isNotBlank()) state.put("last_processed_event_id", lastId)
        state.put(
            "last_processed_event_ts",
            outbox.optLong("last_event_ts", state.optLong("last_processed_event_ts", 0L))
        )
        state.remove("pending_life_batch")
        FileManager.write(root, STATE_PATH, state.toString(2))
    }

    private fun updateDrives(root: File, newEventCount: Int, now: Long, batchId: String) {
        val raw = FileManager.read(root, DRIVES_PATH)
        if (raw.isBlank()) return
        val config = try { JSONObject(raw) } catch (e: Exception) {
            preserveCorruptFile(root, DRIVES_PATH, raw)
            Log.w("Ruri", "Drive configuration is malformed; original file was preserved", e)
            return
        }
        if (config.optString("last_life_batch_id") == batchId) return
        val drives = config.optJSONArray("drives") ?: run {
            Log.w("Ruri", "Drive configuration has no drives array; original file was preserved")
            return
        }
        val goals = readJsonOptional(root, "memory/goals.json")?.optJSONArray("active") ?: JSONArray()
        for (index in 0 until drives.length()) {
            val drive = drives.optJSONObject(index) ?: continue
            val current = drive.optDouble("level", 0.25)
            val updated = when (drive.optString("id")) {
                "curiosity" -> if (newEventCount > 0) current + min(0.15, newEventCount * 0.025) else current - 0.01
                "development" -> if (newEventCount > 0) current + 0.01 else current - 0.003
                "completion" -> (0.25 + goals.length() * 0.12).coerceAtMost(0.95)
                else -> current
            }.coerceIn(0.0, 1.0)
            drive.put("level", updated)
            drive.put("updated_at", now)
        }
        config.put("drives", drives)
        config.put("last_life_batch_id", batchId)
        config.put("evaluated_at", now)
        FileManager.write(root, DRIVES_PATH, config.toString(2))
    }

    private fun auditMemory(root: File, now: Long): JSONObject {
        val checked = JSONArray()
        val invalid = JSONArray()
        val paths = listOf(
            "core/constitution.json",
            "core/character.json",
            "core/drives.json",
            "core/permissions.json",
            "core/config.json",
            "memory/L1_facts.json",
            "memory/L2_diary.json",
            "memory/L3_summary.json",
            "memory/L4_state.json",
            "memory/goals.json",
            "memory/self_model.json",
            "memory/fact_extraction_pending.json"
        )
        for (path in paths) {
            checked.put(path)
            val raw = FileManager.read(root, path)
            if (raw.isBlank() || runCatching { JSONObject(raw) }.isFailure) {
                invalid.put(path)
                preserveCorruptFile(root, path, raw)
            }
        }

        var eventCount = 0
        var malformedEvents = 0
        val l0 = File(root, L0_PATH)
        if (l0.exists()) {
            l0.useLines(Charsets.UTF_8) { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    eventCount++
                    if (runCatching { JSONObject(line) }.isFailure) malformedEvents++
                }
            }
        }

        return JSONObject()
            .put("checked_at", now)
            .put("checked_json_files", checked)
            .put("invalid_json_files", invalid)
            .put("l0_event_count", eventCount)
            .put("l0_malformed_lines", malformedEvents)
            .put("healthy", invalid.length() == 0 && malformedEvents == 0)
    }

    private fun readStateStrict(root: File): JSONObject {
        val raw = FileManager.read(root, STATE_PATH)
        if (raw.isNotBlank()) {
            try { return JSONObject(raw) } catch (e: Exception) {
                Log.w("Ruri", "L4 state is malformed; reconstructing a baseline while preserving its backup", e)
            }
        } else if (!FileManager.exists(root, STATE_PATH)) {
            Log.w("Ruri", "L4 state is missing; reconstructing a baseline from local records")
        } else {
            Log.w("Ruri", "L4 state is empty; reconstructing a baseline")
        }
        return recoverStateFromHistory(root, raw)
    }

    internal fun recoverStateFromHistory(root: File, raw: String): JSONObject {
        preserveCorruptFile(root, STATE_PATH, raw)
        val latestCommander = FileManager.readLastLines(
            root, L0_PATH, maxLines = 12000, maxBytes = 8 * 1024 * 1024
        ).asReversed().asSequence()
            .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
            .firstOrNull { it.optString("type") == "message" && it.optString("who") == "commander" }
        val goalState = readJsonOptional(root, "memory/goals.json") ?: JSONObject()
        val goals = goalState.optJSONArray("active") ?: JSONArray()
        val activeTasks = goalState.optJSONArray("active_tasks") ?: JSONArray()
        val recovered = JSONObject()
            .put("schema_version", 2)
            .put("mood", "восстанавливающаяся")
            .put("energy", 5)
            .put("bond", 0.5)
            .put("needs", JSONObject().put("live", 0.5).put("love_commander", 0.5))
            .put("waiting_for_reply", false)
            .put("ignored_count", 0)
            .put("offended_until", 0L)
            .put("last_seen_commander", 0L)
            .put("last_processed_event_offset", 0L)
            .put("last_thought", 0L)
            .put("active_goals", JSONArray(goals.toString()))
            .put("active_tasks", JSONArray(activeTasks.toString()))
            .put("current_focus", "Восстановление состояния из исторического журнала L0")
            .put("state_recovered_at", System.currentTimeMillis())
            .put("state_recovery_reason", "L4 повреждён; исходная копия сохранена в backup, L0 будет обработан повторно")
        FileManager.write(root, STATE_PATH, recovered.toString(2))
        return recovered
    }

    private fun readJsonOptional(root: File, path: String): JSONObject? {
        val raw = FileManager.read(root, path)
        if (raw.isBlank()) return null
        return try { JSONObject(raw) } catch (e: Exception) {
            preserveCorruptFile(root, path, raw)
            Log.w("Ruri", "Cannot read $path; original file was preserved", e)
            null
        }
    }

    private fun preserveCorruptFile(root: File, path: String, raw: String) {
        if (raw.isBlank()) return
        try {
            val name = path.replace('/', '_').replace('\\', '_')
            val backup = File(root, "backup/" + name + ".corrupt_" + System.currentTimeMillis() + ".json")
            backup.parentFile?.mkdirs()
            if (!backup.exists()) backup.writeText(raw, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("Ruri", "Could not preserve damaged data file: $path", e)
        }
    }
}
