package com.ruri

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {
    @Test
    fun redactsCredentialValuesButKeepsOrdinaryPersonalContext() {
        val event = JSONObject()
            .put("type", "message")
            .put("who", "commander")
            .put("text", "Живу в Лилле. Пароль: hunter2; код подтверждения: 483920; API key: sk_test_1234567890.")
            .put("password", "demo")
            .put("authorization", "demo")
            .put("token", "demo")
        val safe = SecretRedactor.redactEvent(event).toString()

        assertTrue(safe.contains("Живу в Лилле"))
        assertTrue(safe.contains(SecretRedactor.PLACEHOLDER))
        assertFalse(safe.contains("hunter2"))
        assertFalse(safe.contains("483920"))
        assertFalse(safe.contains("sk_test_1234567890"))
        assertFalse(safe.contains("direct_secret_value"))
        assertFalse(safe.contains("\"authorization\":\"demo\""))
        assertFalse(safe.contains("\"token\":\"demo\""))
    }

    @Test
    fun appendEventStoresRedactedTextAtTheL0Boundary() {
        val root = java.nio.file.Files.createTempDirectory("ruri-secret-redaction").toFile()
        val stored = FileManager.appendEvent(
            root,
            JSONObject().put("type", "message").put("who", "commander")
                .put("text", "Пароль: demo")
        )
        assertFalse(stored.contains("Пароль: demo"))
        assertTrue(stored.contains(SecretRedactor.PLACEHOLDER))
        root.deleteRecursively()
    }

    @Test
    fun redactsCardNumbersThatPassLuhn() {
        val safe = SecretRedactor.redactText("Моя карта: 4111 1111 1111 1111")
        assertFalse(safe.contains("4111"))
        assertTrue(safe.contains(SecretRedactor.PLACEHOLDER))
    }

    @Test
    fun redactsCardNumberWhenExplicitlyLabelledEvenIfChecksumIsInvalid() {
        val safe = SecretRedactor.redactText("Номер карты: 1234 5678 9012 3456")
        assertFalse(safe.contains("1234 5678 9012 3456"))
        assertTrue(safe.contains(SecretRedactor.PLACEHOLDER))
    }

    @Test
    fun doesNotRedactUnlabelledOrdinaryNumbersOrProjectContext() {
        val input = "Проект RU-R1, живу в Лилле, встреча в 19:30, версия 1.5."
        assertTrue(SecretRedactor.redactText(input).contains(input))
    }
}
