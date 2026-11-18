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
    private const val DIARY_PATH = "memory/L2_diary.json"
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

        val commanderMessages = newEvents.filter {
            it.optString("type") == "message" && it.optString("who") == "commander"
        }
        val latestCommanderMessage = commanderMessages.maxOfOrNull {
            it.optLong("timestamp", it.optLong("ts", 0L))
        } ?: 0L

        if (latestCommanderMessage > state.optLong("last_seen_commander", 0L)) {
            state.put("last_seen_commander", latestCommanderMessage)
            state.put("waiting_for_reply", false)
            state.put("ignored_count", 0)
            state.put("offended_until", 0L)
        }

        val battery = BatteryLevel.get(context)
        val freeBytes = try { StatFs(root.absolutePath).availableBytes } catch (_: Exception) { -1L }
        val lastSeen = state.optLong("last_seen_commander", 0L)
        val quietHours = if (lastSeen <= 0L) 0.0 else
            ((now - lastSeen).coerceAtLeast(0L) / 3_600_000.0)

        // The two constitutional needs are equal. Their dynamic values only express urgency.
        val liveNeed = when {
            battery <= 12 || (freeBytes in 0 until 100L * 1024L * 1024L) -> 0.95
            battery <= 25 || (freeBytes in 0 until 500L * 1024L * 1024L) -> 0.72
            else -> 0.38
        }
        val connectionNeed = min(0.95, 0.35 + (quietHours / 18.0) * 0.5)
        val needs = state.optJSONObject("needs") ?: JSONObject()
        needs.put("live", liveNeed)
        needs.put("love_commander", connectionNeed)
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
        device.put(
            "resource_mode",
            if (battery <= 15 || (freeBytes in 0 until 100L * 1024L * 1024L)) "conserve" else "normal"
        )
        state.put("device", device)

        val typeCounts = JSONObject()
        newEvents.forEach { event ->
            val type = event.optString("type", "unknown").ifBlank { "unknown" }
            typeCounts.put(type, typeCounts.optInt(type, 0) + 1)
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
            updateDrives(root, 0, battery, now, "cycle:${now}")
            state.put("last_processed_event_offset", batch.nextOffset)
            FileManager.write(root, STATE_PATH, state.toString(2))
        }

        if (now - state.optLong("last_memory_audit", 0L) >= DAY_MS) {
            val report = auditMemory(root, now)
            state.put("memory_health", report)
            state.put("last_memory_audit", now)
            appendMaintenanceDiary(root, report, now)
            FileManager.write(root, STATE_PATH, state.toString(2))
        }
        return state
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
        val battery = outbox.optInt("battery_percent", 50)
        val processedAt = outbox.optLong("processed_at", System.currentTimeMillis())
        val eventCount = outbox.optInt("event_count", 0)
        val recordCount = outbox.optInt("record_count", eventCount)
        val types = outbox.optJSONObject("event_types") ?: JSONObject()
        val endOffset = outbox.optLong("end_offset", 0L)

        updateDrives(root, eventCount, battery, processedAt, batchId)
        if (eventCount > 0) {
            appendActivityDiary(
                root,
                batchId,
                eventCount,
                recordCount,
                outbox.optInt("malformed_count", 0),
                types,
                battery,
                processedAt
            )
        }

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

    private fun updateDrives(root: File, newEventCount: Int, battery: Int, now: Long, batchId: String) {
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
                "resource_recovery" -> when {
                    battery <= 15 -> 0.95
                    battery <= 30 -> 0.75
                    battery <= 50 -> 0.5
                    else -> 0.2
                }
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
            "memory/self_model.json"
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

    private fun appendActivityDiary(
        root: File,
        batchId: String,
        eventCount: Int,
        recordCount: Int,
        malformedCount: Int,
        eventTypes: JSONObject,
        battery: Int,
        now: Long
    ) {
        val raw = FileManager.read(root, DIARY_PATH)
        if (raw.isBlank()) {
            Log.w("Ruri", "Diary is empty; activity summary was not written")
            return
        }
        val diary = try { JSONObject(raw) } catch (e: Exception) {
            preserveCorruptFile(root, DIARY_PATH, raw)
            Log.w("Ruri", "Diary is malformed; original file was preserved", e)
            return
        }
        if (diary.optString("last_activity_batch_id") == batchId) return
        val entries = diary.optJSONArray("entries") ?: JSONArray()
        val keys = eventTypes.keys()
        val parts = ArrayList<String>()
        while (keys.hasNext()) {
            val key = keys.next()
            parts.add(key + ": " + eventTypes.optInt(key))
        }
        val details = parts.joinToString(", ").take(500)
        val text = "Обработала $eventCount событий из $recordCount записей L0 ($details). " +
            "Некорректных строк: $malformedCount. Заряд устройства: $battery%. " +
            "Исходные события остались в журнале L0."
        val previous = if (entries.length() > 0) entries.optJSONObject(entries.length() - 1) else null
        if (previous != null &&
            previous.optString("kind") == "activity_summary" &&
            now - previous.optLong("ts", 0L) < 10L * 60L * 1000L
        ) {
            previous.put("ts", now)
            previous.put("text", text)
            previous.put("event_count", previous.optInt("event_count") + eventCount)
            previous.put("record_count", previous.optInt("record_count") + recordCount)
            previous.put("event_types", eventTypes)
            previous.put("battery_percent", battery)
            previous.put("batch_id", batchId)
        } else {
            val sequence = maxOf(diary.optLong("total_entries", entries.length().toLong()), entries.length().toLong()) + 1L
            entries.put(
                JSONObject()
                    .put("sequence", sequence)
                    .put("ts", now)
                    .put("kind", "activity_summary")
                    .put("text", text)
                    .put("event_count", eventCount)
                    .put("record_count", recordCount)
                    .put("event_types", eventTypes)
                    .put("battery_percent", battery)
                    .put("batch_id", batchId)
            )
            diary.put("total_entries", sequence)
        }
        while (entries.length() > 500) entries.remove(0)
        diary.put("entries", entries)
        diary.put("last_activity_batch_id", batchId)
        diary.put("updated_at", now)
        FileManager.write(root, DIARY_PATH, diary.toString(2))
    }

    private fun appendMaintenanceDiary(root: File, report: JSONObject, now: Long) {
        val raw = FileManager.read(root, DIARY_PATH)
        if (raw.isBlank()) return
        val diary = try { JSONObject(raw) } catch (e: Exception) {
            preserveCorruptFile(root, DIARY_PATH, raw)
            return
        }
        val auditId = "audit:$now"
        if (diary.optString("last_maintenance_audit_id") == auditId) return
        val entries = diary.optJSONArray("entries") ?: JSONArray()
        val checkedCount = report.optJSONArray("checked_json_files")?.length() ?: 0
        val text = if (report.optBoolean("healthy")) {
            "Провела плановую локальную проверку памяти: проверены $checkedCount JSON-файлов и " +
                report.optInt("l0_event_count") + " записей L0; ошибок структуры не обнаружено."
        } else {
            "Провела плановую проверку памяти и обнаружила повреждения. Диагностические копии сохранены, исходные файлы не удаляла."
        }
        val sequence = maxOf(diary.optLong("total_entries", entries.length().toLong()), entries.length().toLong()) + 1L
        entries.put(
            JSONObject().put("sequence", sequence).put("ts", now).put("kind", "maintenance")
                .put("text", text).put("health", report).put("batch_id", auditId)
        )
        while (entries.length() > 500) entries.remove(0)
        diary.put("entries", entries)
        diary.put("total_entries", sequence)
        diary.put("last_maintenance_audit_id", auditId)
        diary.put("updated_at", now)
        FileManager.write(root, DIARY_PATH, diary.toString(2))
    }

    private fun readStateStrict(root: File): JSONObject {
        val raw = FileManager.read(root, STATE_PATH)
        if (raw.isBlank()) {
            if (!FileManager.exists(root, STATE_PATH)) {
                FileManager.init(root)
                return readStateStrict(root)
            }
            preserveCorruptFile(root, STATE_PATH, raw)
            throw IllegalStateException("L4 state is empty; source file was preserved")
        }
        return try { JSONObject(raw) } catch (e: Exception) {
            preserveCorruptFile(root, STATE_PATH, raw)
            throw IllegalStateException("L4 state is malformed; source file was preserved", e)
        }
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
