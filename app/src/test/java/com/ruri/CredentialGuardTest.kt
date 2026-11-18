package com.ruri

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialGuardTest {
    @Test
    fun protectsOnlyAuthenticationCredentials() {
        assertTrue(CredentialGuard.containsCredentials("My password is hunter2"))
        assertTrue(CredentialGuard.containsCredentials("API key: abc123"))
        assertTrue(CredentialGuard.containsCredentials("Ваш код подтверждения: 123456"))
        assertTrue(CredentialGuard.containsCredentials("Bearer eyJhbGciOi..."))
        assertEquals("[учётные данные скрыты]", CredentialGuard.redact("API key: abc123"))
    }

    @Test
    fun leavesOrdinaryPersonalInformationVisible() {
        val examples = listOf(
            "Мой email commander@example.com",
            "Мой телефон +33 6 12 34 56 78",
            "У меня есть медицинская история, которую важно помнить",
            "Я живу в Рубе, рядом с Лиллем",
            "Я хочу обсудить свои политические взгляды и религиозные убеждения",
            "Мы обсуждали наши отношения и планы",
            "У меня есть личные секреты детства, которыми я хочу поделиться",
            "В моей истории были тайны семьи и важные воспоминания"
        )
        examples.forEach { value ->
            assertFalse("Unexpected credential match for: $value", CredentialGuard.containsCredentials(value))
            assertEquals(value, CredentialGuard.redact(value))
        }
    }
}
