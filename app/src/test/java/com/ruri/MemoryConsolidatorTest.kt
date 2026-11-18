package com.ruri

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryConsolidatorTest {
    @Test
    fun offlineSummaryRetainsPreviousBiographyAndPermittedPersonalContext() {
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
        assertTrue(CredentialGuard.containsCredentials("Пароль: hunter2"))
        assertFalse(summary.contains("hunter2"))
    }
}
