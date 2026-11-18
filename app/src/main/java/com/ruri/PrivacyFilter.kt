package com.ruri

/**
 * Conservative local redaction for derived context sent to remote model providers.
 * Original messages and notifications remain in local storage.
 */
object PrivacyFilter {
    private val sensitivePattern = Regex(
        "(?i)(\\b(password|passwd|passphrase|secret|api[ _-]?key|access[ _-]?token|bearer|otp|one[- ]time code|verification code|pin|credit card|debit card|bank account|diagnosis|medication|medical|religion|political|sexual)\\b|" +
            "парол\\p{L}*|секрет\\p{L}*|токен\\p{L}*|ключ\\s+api|код\\s*(подтверждения|из\\s*sms|одноразовый)|банков\\p{L}*|номер\\s+карты|" +
            "диагноз\\p{L}*|болезн\\p{L}*|лекарств\\p{L}*|медицин\\p{L}*|религи\\p{L}*|вероисповед\\p{L}*|политическ\\p{L}*|сексуальн\\p{L}*|" +
            "домашн\\p{L}*\\s+адрес|точн\\p{L}*\\s+адрес|координат\\p{L}*|геолокац\\p{L}*)"
    )
    private val emailPattern = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val phonePattern = Regex("(?<!\\p{L})\\+?\\d[\\d\\s().-]{6,}\\d(?!\\p{L})")

    fun containsSensitive(value: String): Boolean =
        sensitivePattern.containsMatchIn(value) ||
            emailPattern.containsMatchIn(value) ||
            phonePattern.containsMatchIn(value)

    fun redact(value: String, placeholder: String = "[содержимое скрыто фильтром приватности]"): String =
        if (containsSensitive(value)) placeholder else value
}
