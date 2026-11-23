package com.ruri

import org.json.JSONArray
import org.json.JSONObject

/** Redacts authentication material while preserving ordinary personal context. */
object SecretRedactor {
    const val PLACEHOLDER = "[REDACTED_SECRET]"

    private val sensitiveKey = Regex(
        "(?iu).*(?:password|passwd|passphrase|pwd|парол|otp|verification.?code|confirmation.?code|authorization|credential|" +
            "token|api.?key|api[- ]?ключ|client.?secret|private.?key|secret|card.?number|card.?details|номер.?карты).*"
    )
    private val labelledSecrets = listOf(
        Regex("(?iu)(?<![\\p{L}\\p{N}_])((?:password|passwd|passphrase|pwd|пароль|пароля|паролем))(?![\\p{L}\\p{N}_])\\s*(?:[:=]\\s*|\\s+)([^\\s,;]{4,})"),
        Regex("(?iu)(?<![\\p{L}\\p{N}_])((?:код\\s+подтверждения|код\\s+из\\s+(?:sms|смс)|смс[- ]код|verification\\s+code|one[- ]time\\s+code|otp))(?![\\p{L}\\p{N}_])\\s*(?:[:=№#]\\s*|\\s+)([0-9]{4,10})"),
        Regex("(?iu)(?<![\\p{L}\\p{N}_])((?:код\\s+(?:доступа|входа)(?:\\s+к\\s+[^:=\\n]{1,80})?|pin|пин[- ]код))(?![\\p{L}\\p{N}_])\\s*(?:[:=№#]\\s*|\\s+)([A-Za-z0-9._-]{4,16})"),
        Regex("(?iu)(?<![\\p{L}\\p{N}_])((?:api[_ -]?key|api[- ]?ключ|access[_ -]?token|refresh[_ -]?token|auth[_ -]?token|client[_ -]?secret|private[_ -]?key|token|secret|ключ\\s+(?:api|доступа|авторизации)))(?![\\p{L}\\p{N}_])\\s*(?:[:=]\\s*|\\s+)([A-Za-z0-9._~+/-]{8,})"),
        Regex("(?iu)(?<![\\p{L}\\p{N}_])((?:номер(?:\\s+банковской)?\\s+карты|card\\s+number|cardnumber))(?![\\p{L}\\p{N}_])\\s*(?:[:=]\\s*|\\s+)([0-9][0-9 -]{11,24})"),
        Regex("(?iu)(?<![\\p{L}\\p{N}_])(bearer)\\s+([A-Za-z0-9._~+/-]{8,})")
    )
    private val cardCandidate = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")
    private val unlabelledTokenPatterns = listOf(
        Regex("(?i)\\b(?:sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{20,}|AIza[0-9A-Za-z_-]{35}|AKIA[0-9A-Z]{16})\\b"),
        Regex("(?u)\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b")
    )

    fun redactText(input: String): String {
        var text = input
        for (pattern in unlabelledTokenPatterns) text = pattern.replace(text, PLACEHOLDER)
        for (pattern in labelledSecrets) {
            text = pattern.replace(text) { match ->
                match.groupValues[1] + ": " + PLACEHOLDER
            }
        }
        text = cardCandidate.replace(text) { match ->
            if (passesLuhn(match.value.filter(Char::isDigit))) PLACEHOLDER else match.value
        }
        return text
    }

    fun redactEvent(input: JSONObject): JSONObject {
        val copy = JSONObject(input.toString())
        redactObject(copy)
        return copy
    }

    private fun redactObject(value: JSONObject) {
        val keys = value.keys()
        val pending = mutableListOf<Pair<String, Any?>>()
        while (keys.hasNext()) {
            val key = keys.next()
            val child = value.opt(key)
            pending += key to child
        }
        pending.forEach { (key, child) ->
            if (sensitiveKey.matches(key)) {
                if (child != null && child !== JSONObject.NULL) value.put(key, PLACEHOLDER)
            } else {
                when (child) {
                    is JSONObject -> redactObject(child)
                    is JSONArray -> redactArray(child)
                    is String -> value.put(key, redactText(child))
                }
            }
        }
    }

    private fun redactArray(value: JSONArray) {
        for (index in 0 until value.length()) {
            when (val child = value.opt(index)) {
                is JSONObject -> redactObject(child)
                is JSONArray -> redactArray(child)
                is String -> value.put(index, redactText(child))
            }
        }
    }

    private fun passesLuhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        var sum = 0
        var doubleDigit = false
        for (index in digits.indices.reversed()) {
            var digit = digits[index].digitToInt()
            if (doubleDigit) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            doubleDigit = !doubleDigit
        }
        return sum % 10 == 0
    }
}
