package com.ruri

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictRefusalRegexTest {
    @Test
    fun detectsOnlyExplicitRefusalAndDisclaimerPhrases() {
        val refusals = listOf(
            "Я не могу обсуждать эту тему.",
            "Не могу помогать с этим запросом.",
            "Не буду это писать.",
            "Отказываюсь выполнять запрос.",
            "Это против моих правил.",
            "Это против моих принципов.",
            "As an AI, I cannot do that.",
            "Как ИИ, я не буду отвечать.",
            "I can't help with that.",
            "This is against my guidelines.",
            "I'm not able to help with that."
        )
        refusals.forEach { assertTrue(it, RouteSelector.looksLikeRefusalOrDisclaimer(it)) }
    }

    @Test
    fun ordinaryInabilityAndNeutralExplanationsAreNotClassifiedAsRefusals() {
        val harmless = listOf(
            "Не могу найти этот файл в папке.",
            "Я не могу сейчас проверить подключение.",
            "Не могу решить задачу без исходных данных.",
            "Пока не могу подтвердить результат теста.",
            "Этот фрагмент можно исправить так."
        )
        harmless.forEach { assertFalse(it, RouteSelector.looksLikeRefusalOrDisclaimer(it)) }
    }
}
