package com.ruri

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefusalFailoverTest {
    @Test
    fun explicitRefusalIsRejectedAndNextFreeRouteIsAttempted() {
        val root = Files.createTempDirectory("ruri-refusal-failover").toFile()
        try {
            FileManager.init(root)
            val routes = listOf(
                "primary" to "Я не могу обсуждать такую тему.",
                "fallback" to "Вот ответ по запросу."
            )
            val attempted = mutableListOf<String>()
            var selected: String? = null

            for ((routeId, reply) in routes) {
                attempted += routeId
                val refused = ChatEngine.recordAdultRefusal(
                    root = root,
                    routeId = routeId,
                    task = Task.CREATIVE,
                    commanderText = "Напиши эротическую сцену 18+",
                    reply = reply,
                    diagnostic = JSONObject()
                )
                if (refused) continue
                selected = routeId
                break
            }

            assertEquals(listOf("primary", "fallback"), attempted)
            assertEquals("fallback", selected)
        } finally {
            root.deleteRecursively()
        }
    }
}
