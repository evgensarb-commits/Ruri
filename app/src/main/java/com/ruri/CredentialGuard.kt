package com.ruri

/**
 * Protects only machine credentials and authentication secrets from model context.
 * Ordinary personal information (health, relationships, beliefs, location, email and
 * phone numbers) remains available when the Commander has enabled those data sources.
 */
object CredentialGuard {
    private val credentialPattern = Regex(
        "(\\b(password|passwd|passphrase|api[ _-]?key|access[ _-]?token|refresh[ _-]?token|bearer|client secret|private key|one[- ]time password|otp|verification code|authentication code)\\b|" +
            "парол\\p{L}*|ключ\\s+api|токен\\s+доступа|одноразов\\p{L}*\\s+код|код\\s+подтверждения|пин[- ]?код)"
    )

    fun containsCredentials(value: String): Boolean =
        credentialPattern.containsMatchIn(value.lowercase(java.util.Locale.ROOT))

    fun redact(value: String, placeholder: String = "[учётные данные скрыты]"): String =
        if (containsCredentials(value)) placeholder else value
}
