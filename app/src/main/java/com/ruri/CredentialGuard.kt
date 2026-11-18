package com.ruri

/**
 * Protects only machine credentials and authentication secrets from being copied
 * into ordinary model context or autobiographical facts. It does not filter personal
 * life data such as health, relationships, beliefs, addresses, email or phone numbers.
 */
object CredentialGuard {
    private val credentialPattern = Regex(
        "(?i)(\\b(password|passwd|passphrase|api[ _-]?key|access[ _-]?token|refresh[ _-]?token|bearer|client secret|private key|one[- ]time password|otp|verification code|authentication code)\\b|" +
            "парол\\p{L}*|секрет\\p{L}*|токен\\p{L}*|ключ\\s+api|код\\s+подтверждения|одноразов\\w*\\s+код|пин[- ]?код)"
    )

    fun containsCredentials(value: String): Boolean = credentialPattern.containsMatchIn(value)

    fun redact(value: String, placeholder: String = "[учётные данные скрыты]"): String =
        if (containsCredentials(value)) placeholder else value
}
