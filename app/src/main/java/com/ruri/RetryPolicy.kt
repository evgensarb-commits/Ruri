package com.ruri

/**
 * Decides how a failed request is treated.
 *
 * Temporary failures are retried automatically every [AUTO_RETRY_DELAY_MS]; the delay is
 * persisted in [CommanderReplyQueue] so it survives process death and Doze. Terminal
 * failures are never retried automatically — only the «ПОВТОРИТЬ» button in the chat can
 * trigger another attempt, because waiting cannot fix them.
 */
object RetryPolicy {
    /** Automatic retry cadence for transient failures. */
    const val AUTO_RETRY_DELAY_MS = 10_000L

    /** After this many transient failures the app stops retrying and waits for the button. */
    const val MAX_AUTO_ATTEMPTS = 60

    /** Failures where another attempt cannot succeed until a human changes something. */
    private val terminalKinds = setOf(
        "no_key",      // no API key in settings
        "offline",     // Android reports no usable network
        "no_vpn",      // MeroMero requires a VPN tunnel
        "auth",        // HTTP 401 — provider rejected the key
        "no_credits",  // HTTP 402 — balance is empty
        "quota",       // daily token limit exhausted
        "forbidden",   // HTTP 403 without a quota hint
        "region",      // HTTP 451/403 — blocked for this region
        "bad_request", // HTTP 4xx — request/config is wrong (incl. 404 unknown model)
        "config",      // local configuration problem
        "refusal"      // the model declined on its own
    )

    fun isTerminal(kind: String): Boolean = kind in terminalKinds

    fun isRetryable(kind: String): Boolean = !isTerminal(kind)

    /** Persisted delay before the next automatic attempt; 0 means "only by button". */
    fun autoDelayMs(kind: String, attempt: Int = 1): Long = when {
        isTerminal(kind) -> 0L
        attempt >= MAX_AUTO_ATTEMPTS -> 0L
        else -> AUTO_RETRY_DELAY_MS
    }

    /** Machine token shown in the log line. */
    fun label(kind: String): String = when (kind) {
        "no_key" -> "нет ключа"
        "offline" -> "нет сети"
        "no_vpn" -> "нет VPN"
        "auth" -> "ключ отклонён"
        "no_credits" -> "нет кредитов"
        "quota" -> "лимит токенов"
        "forbidden" -> "доступ запрещён"
        "region" -> "регион заблокирован"
        "bad_request" -> "неверный запрос"
        "rate_limit" -> "слишком часто"
        "server" -> "сбой провайдера"
        "timeout" -> "тайм-аут"
        "network" -> "сетевая ошибка"
        "empty_response" -> "пустой ответ"
        "bad_response" -> "нечитаемый ответ"
        "config" -> "ошибка настроек"
        "refusal" -> "отказ модели"
        "ok" -> "успех"
        else -> kind
    }

    /** Human explanation: what exactly is wrong and what to do about it. */
    fun explain(kind: String, httpStatus: Int? = null, detail: String = ""): String {
        val tail = if (detail.isBlank()) "" else " Ответ провайдера: $detail"
        return when (kind) {
            "no_key" ->
                "Не задан API-ключ Routeway. Открой «Настройки» → «Подключение» и вставь ключ с routeway.ai."
            "offline" ->
                "Android сообщает, что интернета нет. Включи сеть — запрос уйдёт, как только связь появится."
            "no_vpn" ->
                "Gemma MeroMero доступна только через VPN. Включи VPN и нажми «ПОВТОРИТЬ»: без туннеля провайдер не ответит."
            "auth" ->
                "Провайдер отклонил ключ (HTTP 401). Ключ неверный или отозван — проверь его на routeway.ai и сохрани заново."
            "no_credits" ->
                "На балансе Routeway нет кредитов (HTTP 402). Пополни баланс, иначе запросы не пройдут."
            "quota" ->
                "Исчерпан дневной лимит токенов. Автоматических повторов не будет: лимит сбрасывается в 00:00 UTC — нажми «ПОВТОРИТЬ» после сброса."
            "forbidden" ->
                "Провайдер запретил доступ (HTTP 403). Проверь регион, VPN и условия тарифа.$tail"
            "region" ->
                "Провайдер блокирует этот регион (HTTP ${httpStatus ?: 403}). Включи VPN и нажми «ПОВТОРИТЬ».$tail"
            "bad_request" ->
                "Провайдер отклонил запрос (HTTP ${httpStatus ?: 400}). Обычно это неверная модель или адрес в настройках.$tail"
            "rate_limit" ->
                "Слишком много запросов подряд (HTTP 429). Повторю автоматически через 10 секунд."
            "server" ->
                "Сбой на стороне провайдера (HTTP ${httpStatus ?: "5xx"}). Повторю автоматически через 10 секунд.$tail"
            "timeout" ->
                "Сервер не ответил за отведённое время. Повторю автоматически через 10 секунд.$tail"
            "network" ->
                "Сетевая ошибка при соединении. Повторю автоматически через 10 секунд.$tail"
            "empty_response" ->
                "Провайдер вернул пустой ответ. Повторю автоматически через 10 секунд."
            "bad_response" ->
                "Ответ провайдера не удалось разобрать. Повторю автоматически через 10 секунд.$tail"
            "config" ->
                "Локальная настройка маршрута повреждена: ${detail.ifBlank { "неверный адрес провайдера" }}."
            "refusal" ->
                "Модель отказалась отвечать на этот запрос. Это не сбой связи — переформулируй или нажми «ПОВТОРИТЬ»."
            else ->
                "Запрос не удался: ${detail.ifBlank { kind }}."
        }
    }
}
