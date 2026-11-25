package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

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
    fun localFallbackBuildsAutobiographyFromFactsOnlyAndNeverFromThoughts() {
        val selfModel = JSONObject()
            .put("identity", "Рури")
            .put("known_capabilities", JSONArray().put("chat").put("local_memory"))
        val character = JSONObject().put("name", "Рури").put("designation", "RU-R1")
        val facts = JSONArray()
            .put(JSONObject().put("text", "Командир разрабатывает проект RU-R1").put("category", "проекты"))
            .put(JSONObject().put("text", "Командир любит чай с молоком").put("category", "предпочтения"))
        // A diary-like line must never appear: it is not passed in at all.
        val thoughts = "Я скучала и не понимала, куда он пропал на весь вечер."

        val summary = MemoryConsolidator.localFactSummary(selfModel, character, facts)

        assertTrue(summary.contains("Рури"))
        assertTrue(summary.contains("RU-R1"))
        assertTrue(summary.contains("Командир разрабатывает проект RU-R1"))
        assertTrue(summary.contains("Командир любит чай с молоком"))
        assertFalse("autobiography must not contain diary prose", summary.contains("скучала"))
        assertFalse(summary.contains(thoughts))
    }

    @Test
    fun localFallbackRedactsSecretsInsideFacts() {
        val facts = JSONArray()
            .put(JSONObject().put("text", "Проект RU-R1 продолжается. Пароль: hunter2."))
        val summary = MemoryConsolidator.localFactSummary(JSONObject(), JSONObject(), facts)
        assertTrue(summary.contains("Проект RU-R1"))
        assertFalse(summary.contains("hunter2"))
        assertTrue(summary.contains(SecretRedactor.PLACEHOLDER))
    }

    @Test
    fun localFallbackIsHonestWhenThereAreNoFactsYet() {
        val summary = MemoryConsolidator.localFactSummary(JSONObject(), JSONObject(), JSONArray())
        assertTrue(summary.isNotBlank())
        assertTrue(summary.contains("не собрана") || summary.contains("недостаточно"))
    }

    @Test
    fun diaryEchoesAreStrippedFromAnAlreadyPollutedSummary() {
        val thought = "Я скучала весь вечер и думала, куда он пропал."
        val clean = "Рури — искусственная личность на телефоне Командира."
        val polluted = clean + "\n" + thought + "\nКомандир разрабатывает проект RU-R1."
        val entries = JSONArray().put(JSONObject().put("text", thought))

        val healed = MemoryConsolidator.stripDiaryEchoes(polluted, entries)

        assertFalse("a verbatim diary line must be removed", healed.contains(thought))
        assertTrue("real autobiography lines stay", healed.contains(clean))
        assertTrue(healed.contains("Командир разрабатывает проект RU-R1"))
    }

    @Test
    fun strippingEchoesKeepsUnrelatedShortLines() {
        val healed = MemoryConsolidator.stripDiaryEchoes("Кто я: Рури.", JSONArray())
        assertTrue(healed.contains("Кто я: Рури."))
    }
}

/**
 * L3 is facts-driven now, and the Commander can force a rebuild from the interface.
 */
class AutobiographyRebuildTest {
    private val day = 24L * 60L * 60L * 1000L

    @Test fun rebuildIsDueWhenTheFactSetChanged() {
        val now = 10L * day
        // Nothing built yet: due regardless of counts.
        assertTrue(MemoryConsolidator.shouldRebuildFromFacts(5, -1, 0L, now))
        // Same facts and a recent rebuild: not due.
        assertFalse(MemoryConsolidator.shouldRebuildFromFacts(5, 5, now - day / 2, now))
        // A new fact appeared and the cooldown passed: due.
        assertTrue(MemoryConsolidator.shouldRebuildFromFacts(6, 5, now - day * 2, now))
    }

    @Test fun dailyCooldownBlocksRebuildEvenWhenFactsChanged() {
        val now = 10L * day
        // Facts changed one hour ago: wait for the cooldown instead of burning tokens.
        assertFalse(MemoryConsolidator.shouldRebuildFromFacts(9, 5, now - 60L * 60L * 1000L, now))
    }

    @Test fun unchangedFactsDoNotTriggerARebuildAfterTheCooldown() {
        val now = 10L * day
        assertFalse(MemoryConsolidator.shouldRebuildFromFacts(7, 7, now - day * 3, now))
    }

    @Test fun manualRebuildIsExposedToTheInterface() {
        val activity = RepoSources.text("app/src/main/java/com/ruri/MainActivity.kt")
        assertTrue(activity.contains("fun rebuildAutobiography("))
        assertTrue(activity.contains("MemoryConsolidator.rebuildNow"))
        val html = RepoSources.text("app/src/main/assets/index.html")
        assertTrue(html.contains("rebuildAutobio"))
        assertTrue(html.contains("rebuildAutobiography"))
    }

    @Test fun forcedRebuildWritesAFactualSummaryWithoutDiaryText() {
        val dir = Files.createTempDirectory("ruri-l3-rebuild").toFile()
        try {
            FileManager.init(dir)
            // A diary thought that must never reach L3.
            FileManager.write(dir, "memory/L2_diary.json", JSONObject()
                .put("entries", JSONArray().put(JSONObject()
                    .put("id", "d1").put("kind", "thought")
                    .put("text", "Я скучала и не понимала, куда он пропал на весь вечер.")
                    .put("ts", 1L)))
                .toString(2))
            FileManager.write(dir, "memory/L1_facts.json", JSONObject()
                .put("facts", JSONArray()
                    .put(JSONObject().put("id", "f1")
                        .put("text", "Командир разрабатывает проект RU-R1").put("category", "проекты")))
                .toString(2))
            // A polluted summary from an earlier build.
            FileManager.write(dir, "memory/L3_summary.json", JSONObject()
                .put("summary", "Я скучала и не понимала, куда он пропал на весь вечер.\nКомандир любит чай.")
                .put("last_compressed", 0L)
                .toString(2))

            // No provider is reachable in unit tests, so the local facts path is used.
            MemoryConsolidator.rebuildNowRoot(dir)

            val summary = JSONObject(FileManager.read(dir, "memory/L3_summary.json"))
            val text = summary.optString("summary")
            assertTrue("facts must be present", text.contains("RU-R1"))
            assertFalse("diary prose must not survive", text.contains("скучала"))
            assertEquals("local_facts_only", summary.optString("method"))
            assertTrue(summary.optBoolean("rebuilt_manually"))
            assertEquals(1, summary.optInt("source_fact_count"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun forcedRebuildSurvivesAMalformedDiary() {
        val dir = Files.createTempDirectory("ruri-l3-broken-diary").toFile()
        try {
            FileManager.init(dir)
            // A broken L2 must not block the autobiography: it is not an input any more.
            FileManager.write(dir, "memory/L2_diary.json", "{broken")
            FileManager.write(dir, "memory/L1_facts.json", JSONObject()
                .put("facts", JSONArray().put(JSONObject()
                    .put("id", "f1").put("text", "Командир любит чай с молоком").put("category", "предпочтения")))
                .toString(2))
            val changed = MemoryConsolidator.rebuildNowRoot(dir)
            assertTrue(changed)
            val text = JSONObject(FileManager.read(dir, "memory/L3_summary.json")).optString("summary")
            assertTrue(text.contains("Командир любит чай с молоком"))
        } finally { dir.deleteRecursively() }
    }
}
