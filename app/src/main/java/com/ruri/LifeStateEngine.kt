package com.ruri

import android.content.Context
import android.os.StatFs
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.min

/**
 * Local part of RU-R1's life cycle.
 * It works without an LLM: consumes L0 events, updates motivational signals,
 * samples device resources and performs a non-destructive memory audit.
 */
object LifeStateEngine {
    private const val STATE_PATH = "memory/L4_state.json"
    private const val DRIVES_PATH = "core/drives.json"
    private const val L0_PATH = "memory/L0_raw.jsonl"
    private const val DIARY_PATH = "memory/L2_diary.json"
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun process(context: Context, reason: String): JSONObject {
        val root = context.filesDir
        FileManager.init(root)

        val state = readJson(root, STATE_PATH)
        val now = System.currentTimeMillis()
        val previousCursor = state.optString("last_processed_event_id")
        val previousTs = state.optLong("last_processed_event_ts", 0L)
        val events = readEvents(root)
        val cursorIndex = if (previousCursor.isBlank()) -1 else
            events.indexOfFirst { it.optString("id") == previousCursor }

        val newEvents = if (cursorIndex >= 0) {
            events.drop(cursorIndex + 1)
        } else {
            events.filter { it.optLong("timestamp", it.optLong("ts", 0L)) > previousTs }
        }

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

        // Primary need levels are motivational signals, not declarations of felt emotion.
        val liveNeed = when {
            battery <= 12 || (freeBytes in 0 until 100L * 1024L * 1024L) -> 0.95
            battery <= 25 || (freeBytes in 0 until 500L * 1024L * 1024L) -> 0.72
            else -> 0.38
        }
        val connectionNeed = min(0.85, 0.35 + (quietHours / 18.0) * 0.5)
        val needs = state.optJSONObject("needs") ?: JSONObject()
        needs.put("live", liveNeed)
        needs.put("love_commander", connectionNeed)
        needs.put("updated_at", now)
        state.put("needs", needs)

        val device = DeviceSensors.snapshot(context, now)
        device.put("battery_percent", battery)
        device.put("available_storage_bytes", freeBytes)
        device.put("sampled_at", now)
        device.put("resource_mode", if (battery <= 15 || (freeBytes in 0 until 100L * 1024L * 1024L)) "conserve" else "normal")
        state.put("device", device)

        val typeCounts = JSONObject()
        newEvents.forEach { event ->
            val type = event.optString("type", "unknown").ifBlank { "unknown" }
            typeCounts.put(type, typeCounts.optInt(type, 0) + 1)
        }
        val lastNewEvent = newEvents.lastOrNull()
        val recent = JSONObject()
            .put("processed_event_count", newEvents.size)
            .put("event_types", typeCounts)
            .put("last_event_at", lastNewEvent?.optLong("timestamp", lastNewEvent.optLong("ts", 0L)) ?: previousTs)
            .put("updated_at", now)
        state.put("recent_activity", recent)

        val lastEvent = events.lastOrNull()
        if (lastEvent != null) {
            state.put("last_processed_event_id", lastEvent.optString("id"))
            state.put("last_processed_event_ts", lastEvent.optLong("timestamp", lastEvent.optLong("ts", previousTs)))
        }
        state.put("last_local_cycle", now)
        state.put("last_local_reason", reason.take(180))
        state.put("last_quiet_hours", quietHours)
        updateDrives(root, newEvents.size, battery, now)
        if (newEvents.isNotEmpty()) appendActivityDiary(root, newEvents.size, typeCounts, battery, now)

        if (now - state.optLong("last_memory_audit", 0L) >= DAY_MS) {
            val report = auditMemory(root, now)
            state.put("memory_health", report)
            state.put("last_memory_audit", now)
            appendMaintenanceDiary(root, report, now)
        }

        FileManager.write(root, STATE_PATH, state.toString(2))
        return state
    }

    private fun updateDrives(root: File, newEventCount: Int, battery: Int, now: Long) {
        val config = readJson(root, DRIVES_PATH)
        val drives = config.optJSONArray("drives") ?: run {
            Log.w("Ruri", "Drive configuration is missing or invalid; preserved file was not overwritten")
            return
        }
        val goals = readJson(root, "memory/goals.json").optJSONArray("active") ?: JSONArray()
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
        eventCount: Int,
        eventTypes: JSONObject,
        battery: Int,
        now: Long
    ) {
        try {
            val diary = readJson(root, DIARY_PATH)
            val entries = diary.optJSONArray("entries") ?: JSONArray()
            val keys = eventTypes.keys()
            val parts = ArrayList<String>()
            while (keys.hasNext()) {
                val key = keys.next()
                parts.add(key + ": " + eventTypes.optInt(key))
            }
            val details = parts.joinToString(", ").take(500)
            val text = "Обработала " + eventCount + " новых системных событий (" + details +
                "). Уровень заряда устройства при обработке: " + battery + "%. Содержимое личных сообщений и уведомлений в эту сводную запись не копировала."

            val previous = if (entries.length() > 0) entries.optJSONObject(entries.length() - 1) else null
            if (previous != null &&
                previous.optString("kind") == "activity_summary" &&
                now - previous.optLong("ts", 0L) < 10L * 60L * 1000L
            ) {
                previous.put("ts", now)
                previous.put("text", text)
                previous.put("event_count", previous.optInt("event_count") + eventCount)
                previous.put("event_types", eventTypes)
                previous.put("battery_percent", battery)
            } else {
                val sequence = maxOf(diary.optLong("total_entries", entries.length().toLong()), entries.length().toLong()) + 1L
                entries.put(
                    JSONObject()
                        .put("sequence", sequence)
                        .put("ts", now)
                        .put("kind", "activity_summary")
                        .put("text", text)
                        .put("event_count", eventCount)
                        .put("event_types", eventTypes)
                        .put("battery_percent", battery)
                )
                diary.put("total_entries", sequence)
            }
            while (entries.length() > 500) entries.remove(0)
            diary.put("entries", entries)
            diary.put("updated_at", now)
            FileManager.write(root, DIARY_PATH, diary.toString(2))
        } catch (e: Exception) {
            Log.w("Ruri", "Could not write local activity diary entry", e)
        }
    }

    private fun appendMaintenanceDiary(root: File, report: JSONObject, now: Long) {
        try {
            val diary = readJson(root, DIARY_PATH)
            val entries = diary.optJSONArray("entries") ?: JSONArray()
            val checkedCount = report.optJSONArray("checked_json_files")?.length() ?: 0
            val text = if (report.optBoolean("healthy")) {
                "Провела плановую локальную проверку памяти: проверены " + checkedCount +
                    " JSON-файлов и " + report.optInt("l0_event_count") +
                    " событий L0; ошибок структуры не обнаружено."
            } else {
                "Провела плановую проверку памяти и обнаружила повреждения. Сохранила диагностические сведения, исходные файлы не удаляла."
            }
            val sequence = maxOf(diary.optLong("total_entries", entries.length().toLong()), entries.length().toLong()) + 1L
            entries.put(
                JSONObject()
                    .put("sequence", sequence)
                    .put("ts", now)
                    .put("kind", "maintenance")
                    .put("text", text)
                    .put("health", report)
            )
            while (entries.length() > 500) entries.remove(0)
            diary.put("entries", entries)
            diary.put("total_entries", sequence)
            diary.put("updated_at", now)
            FileManager.write(root, DIARY_PATH, diary.toString(2))
        } catch (e: Exception) {
            Log.w("Ruri", "Could not append memory maintenance diary entry", e)
        }
    }

    private fun readEvents(root: File): List<JSONObject> {
        val raw = FileManager.read(root, L0_PATH)
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }.toList()
    }

    private fun readJson(root: File, path: String): JSONObject {
        val raw = FileManager.read(root, path)
        if (raw.isBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            preserveCorruptFile(root, path, raw)
            JSONObject()
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
            Log.w("Ruri", "Could not preserve damaged data file: " + path, e)
        }
    }
}
