package com.ruri

import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextExtractorTest {
    @Test
    fun includesBigTextLinesAndNestedMessageContents() {
        val text = NotificationTextExtractor.extractFromValues(
            mapOf(
                "android.title" to "Переписка",
                "android.text" to "Короткий предпросмотр",
                "android.bigText" to "Полный текст сообщения с дополнительными деталями",
                "android.textLines" to arrayOf("Первая строка", "Вторая строка"),
                "android.messages" to arrayOf(
                    mapOf("sender" to "Анна", "text" to "Первое сообщение в диалоге"),
                    mapOf("sender" to "Командир", "text" to "Ответ с подробностями")
                )
            )
        )

        assertTrue(text.contains("Полный текст сообщения с дополнительными деталями"))
        assertTrue(text.contains("Первая строка"))
        assertTrue(text.contains("Вторая строка"))
        assertTrue(text.contains("Первое сообщение в диалоге"))
        assertTrue(text.contains("Ответ с подробностями"))
        assertTrue(text.contains("Анна"))
    }

    @Test
    fun capturesTextFromNestedCollections() {
        val text = NotificationTextExtractor.extractFromValues(
            mapOf(
                "android.messages" to listOf(
                    mapOf("sender" to "Служба доставки", "text" to "Заказ прибудет завтра")
                )
            )
        )
        assertTrue(text.contains("Служба доставки"))
        assertTrue(text.contains("Заказ прибудет завтра"))
    }
    @Test fun traversalStopsAfterBoundedNumberOfNotificationValues() {
        var visited = 0
        val huge = Iterable<Any?> {
            object : Iterator<Any?> {
                private var index = 0
                override fun hasNext(): Boolean = index < 100_000
                override fun next(): Any? {
                    index++
                    visited++
                    return "notification-line-$index"
                }
            }
        }
        val result = NotificationTextExtractor.extractFromValues(mapOf("messages" to huge))
        assertTrue(visited <= 100)
        assertTrue(result.length <= 20_000)
    }

}
