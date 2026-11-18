package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryConsolidatorTest {
    @Test
    fun consolidationSelectsOldestUncompressedPageAndDoesNotSkipFollowingEntries() {
        val entries = JSONArray()
        repeat(45) { index ->
            entries.put(
                JSONObject()
                    .put("sequence", index + 1)
                    .put("ts", 1_700_000_000_000L + index)
                    .put("text", "Запись дневника номер ${index + 1} с полезным содержанием.")
            )
        }
        val diary = JSONObject().put("total_entries", 45).put("entries", entries)

        val first = MemoryConsolidator.selectEntriesForConsolidation(diary, previousCount = 0L, limit = 40)
        val second = MemoryConsolidator.selectEntriesForConsolidation(diary, previousCount = first.lastSequence, limit = 40)

        assertEquals(40, first.entries.size)
        assertEquals(1L, first.firstSequence)
        assertEquals(40L, first.lastSequence)
        assertEquals(5, second.entries.size)
        assertEquals(41L, second.firstSequence)
        assertEquals(45L, second.lastSequence)
        assertEquals(0L, first.skippedBeforeBatch)
        assertEquals(0L, second.skippedBeforeBatch)
    }

    @Test
    fun legacyRollingDiaryUsesLifetimeCounterToReconstructSequenceNumbers() {
        val entries = JSONArray()
        repeat(500) { index ->
            entries.put(
                JSONObject()
                    .put("ts", index + 1L)
                    .put("text", "Старая запись дневника номер ${index + 1} с контекстом.")
            )
        }
        val diary = JSONObject().put("total_entries", 550L).put("entries", entries)

        val batch = MemoryConsolidator.selectEntriesForConsolidation(diary, previousCount = 520L, limit = 40)

        assertEquals(30, batch.entries.size)
        assertEquals(521L, batch.firstSequence)
        assertEquals(550L, batch.lastSequence)
        assertEquals(0L, batch.skippedBeforeBatch)
    }

    @Test
    fun reportsHistoryAlreadyDroppedFromTheRollingDiaryWindow() {
        val entries = JSONArray()
            .put(JSONObject().put("sequence", 101).put("text", "Сохранённая запись сто один с контекстом."))
            .put(JSONObject().put("sequence", 102).put("text", "Сохранённая запись сто два с контекстом."))
        val diary = JSONObject().put("total_entries", 102).put("entries", entries)

        val batch = MemoryConsolidator.selectEntriesForConsolidation(diary, previousCount = 90L, limit = 40)

        assertEquals(101L, batch.firstSequence)
        assertEquals(102L, batch.lastSequence)
        assertEquals(10L, batch.skippedBeforeBatch)
    }

    @Test
    fun canConsolidateShortRemainingWindowWhenOlderDiaryHistoryWasEvicted() {
        val diary = JSONObject().put("total_entries", 102L).put(
            "entries",
            JSONArray()
                .put(JSONObject().put("sequence", 101L).put("text", "Доступная запись сто один."))
                .put(JSONObject().put("sequence", 102L).put("text", "Доступная запись сто два."))
        )
        val batch = MemoryConsolidator.selectEntriesForConsolidation(diary, previousCount = 90L)
        assertEquals(10L, batch.skippedBeforeBatch)
        assertTrue(
            MemoryConsolidator.shouldConsolidateBatch(
                batch, previousCount = 90L, lastCompressedAt = 0L, now = 2L * 24L * 60L * 60L * 1000L
            )
        )
    }

    @Test
    fun shortDiaryWindowWithoutHistoryGapStillWaitsForMinimumNewEntries() {
        val diary = JSONObject().put("total_entries", 92L).put(
            "entries",
            JSONArray()
                .put(JSONObject().put("sequence", 91L).put("text", "Доступная запись девяносто один."))
                .put(JSONObject().put("sequence", 92L).put("text", "Доступная запись девяносто два."))
        )
        val batch = MemoryConsolidator.selectEntriesForConsolidation(diary, previousCount = 90L)
        assertEquals(0L, batch.skippedBeforeBatch)
        assertFalse(
            MemoryConsolidator.shouldConsolidateBatch(
                batch, previousCount = 90L, lastCompressedAt = 0L, now = 2L * 24L * 60L * 60L * 1000L
            )
        )
    }

    @Test
    fun historyGapDoesNotBypassL3ConsolidationCooldown() {
        val diary = JSONObject().put("total_entries", 102L).put(
            "entries",
            JSONArray()
                .put(JSONObject().put("sequence", 101L).put("text", "Доступная запись сто один."))
                .put(JSONObject().put("sequence", 102L).put("text", "Доступная запись сто два."))
        )
        val batch = MemoryConsolidator.selectEntriesForConsolidation(diary, previousCount = 90L)
        val now = 2L * 24L * 60L * 60L * 1000L
        assertFalse(
            MemoryConsolidator.shouldConsolidateBatch(
                batch, previousCount = 90L, lastCompressedAt = now - 23L * 60L * 60L * 1000L, now = now
            )
        )
    }

    @Test
    fun handlesMixedLegacyAndSequencedDiaryEntries() {
        val entries = JSONArray()
            .put(JSONObject().put("text", "Старая запись первая с достаточным содержанием."))
            .put(JSONObject().put("text", "Старая запись вторая с достаточным содержанием."))
            .put(JSONObject().put("sequence", 103).put("text", "Новая запись третья с достаточным содержанием."))
            .put(JSONObject().put("sequence", 104).put("text", "Новая запись четвёртая с достаточным содержанием."))
        val diary = JSONObject().put("total_entries", 104).put("entries", entries)

        val batch = MemoryConsolidator.selectEntriesForConsolidation(diary, previousCount = 101L, limit = 40)

        assertEquals(3, batch.entries.size)
        assertEquals(102L, batch.firstSequence)
        assertEquals(104L, batch.lastSequence)
        assertTrue(batch.entries.first().optString("text").contains("вторая"))
    }

    @Test
    fun offlineSummaryKeepsAllRecordsFromTheCurrentConsolidationPage() {
        val previous = "Прежняя биографическая сводка о проекте RU-R1."
        val entries = (1..40).map { index ->
            JSONObject().put("text", "Дневниковая запись номер $index содержит уникальное событие и полезный контекст.")
        }

        val summary = MemoryConsolidator.extractiveSummary(previous, entries)

        assertTrue(summary.contains(previous))
        assertTrue(summary.contains("Дневниковая запись номер 1"))
        assertTrue(summary.contains("Дневниковая запись номер 40"))
    }

    @Test
    fun offlineSummaryRedactsSecretsFromPreviousSummaryAndDiary() {
        val previous = "Проект RU-R1 продолжается. Пароль: hunter2."
        val entries = listOf(
            JSONObject().put("text", "Код подтверждения: 483920 для старого входа в аккаунт.")
        )

        val summary = MemoryConsolidator.extractiveSummary(previous, entries)

        assertTrue(summary.contains("Проект RU-R1"))
        assertFalse(summary.contains("hunter2"))
        assertFalse(summary.contains("483920"))
        assertTrue(summary.contains(SecretRedactor.PLACEHOLDER))
    }

    @Test
    fun offlineSummaryRetainsPreviousBiographyAndAllAvailableContext() {
        val previous = "Ранее Рури и Командир начали долгосрочный проект RU-R1."
        val entries = listOf(
            JSONObject().put("text", "Командир живёт в Рубе и часто бывает рядом с Лиллем."),
            JSONObject().put("text", "Командир сообщил email commander@example.com для важного проекта."),
            JSONObject().put("text", "Пароль: hunter2 это секретная учётная строка, не сохранять.")
        )

        val summary = MemoryConsolidator.extractiveSummary(previous, entries)

        assertTrue(summary.contains(previous))
        assertTrue(summary.contains("Рубе"))
        assertTrue(summary.contains("commander@example.com"))
        assertFalse(summary.contains("hunter2"))
        assertTrue(summary.contains(SecretRedactor.PLACEHOLDER))
    }
}
