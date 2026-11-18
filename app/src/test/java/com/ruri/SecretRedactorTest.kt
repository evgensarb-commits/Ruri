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
            .put("password", "direct_secret_value")
        val safe = SecretRedactor.redactEvent(event).toString()

        assertTrue(safe.contains("Живу в Лилле"))
        assertTrue(safe.contains(SecretRedactor.PLACEHOLDER))
        assertFalse(safe.contains("hunter2"))
        assertFalse(safe.contains("483920"))
        assertFalse(safe.contains("sk_test_1234567890"))
        assertFalse(safe.contains("direct_secret_value"))
    }

    @Test
    fun redactsCardNumbersThatPassLuhn() {
        val safe = SecretRedactor.redactText("Моя карта: 4111 1111 1111 1111")
        assertFalse(safe.contains("4111"))
        assertTrue(safe.contains(SecretRedactor.PLACEHOLDER))
    }

    @Test
    fun doesNotRedactUnlabelledOrdinaryNumbersOrProjectContext() {
        val input = "Проект RU-R1, живу в Лилле, встреча в 19:30, версия 1.5."
        assertTrue(SecretRedactor.redactText(input).contains(input))
    }
}
