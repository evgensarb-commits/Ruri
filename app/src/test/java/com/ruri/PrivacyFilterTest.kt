package com.ruri

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyFilterTest {
    @Test
    fun redactsCredentialsAndVerificationCodes() {
        assertTrue(PrivacyFilter.containsSensitive("My password is hunter2"))
        assertEquals("[hidden]", PrivacyFilter.redact("API key: abc123", "[hidden]"))
        assertEquals("[hidden]", PrivacyFilter.redact("Ваш код подтверждения: 123456", "[hidden]"))
    }

    @Test
    fun redactsContactDetails() {
        assertTrue(PrivacyFilter.containsSensitive("Напиши на ruri@example.com"))
        assertEquals("[hidden]", PrivacyFilter.redact("Телефон +33 6 12 34 56 78", "[hidden]"))
    }

    @Test
    fun leavesOrdinaryTextAlone() {
        val value = "Сегодня мы обсуждали план развития Рури и память."
        assertFalse(PrivacyFilter.containsSensitive(value))
        assertEquals(value, PrivacyFilter.redact(value))
    }
}
