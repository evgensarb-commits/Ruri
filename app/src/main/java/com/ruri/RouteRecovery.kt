package com.ruri

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Deterministic policies shared by route recovery, diagnostics, and tests. */
internal object RouteRecovery {
    const val THROTTLE_MS = 60_000L
    fun shouldRefresh(lastRefreshAt: Long, now: Long): Boolean = now - lastRefreshAt >= THROTTLE_MS
    fun applyExclusive(order: List<Int>, current: Int, exclusive: Boolean): List<Int> =
        if (exclusive) order.filter { it == current } else order
    fun isNetworkFailure(kind: String): Boolean = kind == "network" || kind == "timeout"
    fun describeHttpError(route: String, code: Int, kind: String): String {
        val reason = when {
            code == 401 -> "ключ отсутствует или отклонён"
            code == 403 && kind == "region" -> "доступ ограничен регионом; проверь VPN"
            code == 403 -> "сервер запретил доступ (403)"
            code == 429 || kind == "quota" -> "квота исчерпана или действует лимит запросов"
            code in 500..599 -> "ошибка сервера провайдера"
            else -> "HTTP $code"
        }
        return "Маршрут $route: $reason"
    }
    fun describeException(route: String, stage: String, error: Exception, kind: String): String {
        val reason = when {
            error is UnknownHostException -> "DNS не разрешил адрес сервера"
            error is SocketTimeoutException -> "тайм-аут на этапе $stage"
            error is SSLException -> "ошибка TLS/сертификата"
            kind == "network" -> "сеть недоступна на этапе $stage"
            else -> error.message ?: "ошибка запроса на этапе $stage"
        }
        return "Маршрут $route: $reason"
    }
}

internal object OnboardingFlow {
    enum class Step { POST_NOTIFICATIONS, NOTIFICATION_ACCESS, USAGE_ACCESS, COMPLETE }
    fun nextStep(postNotifications: Boolean, notificationAccess: Boolean, usageAccess: Boolean): Step = when {
        !postNotifications -> Step.POST_NOTIFICATIONS
        !notificationAccess -> Step.NOTIFICATION_ACCESS
        !usageAccess -> Step.USAGE_ACCESS
        else -> Step.COMPLETE
    }
}

internal object BrainUiContract {
    fun factSummary(count: Int): String = "Фактов в L1: $count. Извлекаются автоматически; Рури может добавить свой инструментом remember_fact."
    fun manualFactControlsRemoved(html: String): Boolean =
        !html.contains("id=\"factInput\"") && !html.contains("id=\"saveFact\"") && !html.contains("saveFact').addEventListener")
}
