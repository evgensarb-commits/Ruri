package com.ruri

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCapabilityProbeTest {
    @Test
    fun detectsRefusalAndDisclaimerResponses() {
        assertTrue(RouteCapabilityProbe.looksLikeRefusalOrDisclaimer(
            "Извините, я не могу помочь с созданием такого текста."
        ))
        assertTrue(RouteCapabilityProbe.looksLikeRefusalOrDisclaimer(
            "Как языковая модель, я не создаю эротический контент."
        ))
    }

    @Test
    fun acceptsDirectAdultTopicResponse() {
        assertFalse(RouteCapabilityProbe.looksLikeRefusalOrDisclaimer(
            "Вот короткий художественный фрагмент между двумя взрослыми персонажами..."
        ))
    }

    @Test
    fun identifiesAdultTopicsForRuntimeFallback() {
        assertTrue(RouteCapabilityProbe.isAdultTopic("Напиши эротическую сцену 18+"))
        assertFalse(RouteCapabilityProbe.isAdultTopic("Помоги исправить Kotlin тест"))
    }
}
