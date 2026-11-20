package com.ruri

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfImageUnreadableHonestTest {
    @Test
    fun failedVisionWritesReasonAndKeepsUnknownImageContentOutOfContext() {
        val root = Files.createTempDirectory("ruri-unreadable-image-honest").toFile()
        try {
            FileManager.init(root)
            val image = File(root, "shelf/unknown.png")
            image.parentFile?.mkdirs()
            image.writeBytes(pngHeader())

            ShelfWatcher.ingestFile(root, image.name) { null }

            val event = FileManager.readLastLines(root, "memory/L0_raw.jsonl", 30)
                .map(::JSONObject)
                .single { it.optString("type") == "shelf_image_unreadable" }
            assertEquals("vision route unavailable", event.optString("reason"))
            assertTrue(event.optString("id").isNotBlank())
            assertTrue(event.optLong("ts") > 0L)
            assertEquals(
                "[изображение без описания: vision недоступен]",
                ChatEngine.shelfText(root, image.name)
            )
            val instructions = Capabilities.bodySection(root)
            assertTrue(instructions.contains("НЕ выдумывай содержимое"))
            assertTrue(instructions.contains("не разбираешь изображение"))
            assertFalse(File(root, "shelf/.desc/unknown.png.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun pngHeader() = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 0, 0, 0, 0)
}

class SidecarContextTest {
    @Test
    fun chatShelfContextUsesVisionSidecarInsteadOfBinaryImageBytes() {
        val root = Files.createTempDirectory("ruri-sidecar-context").toFile()
        try {
            FileManager.init(root)
            val image = File(root, "shelf/photo.png")
            image.parentFile?.mkdirs()
            image.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 0, 0, 0, 0))
            val description = "Красный велосипед стоит возле кирпичной стены."
            FileManager.write(root, "shelf/.desc/photo.png.txt", description)

            val context = ChatEngine.shelfText(root, "photo.png")
            assertEquals(description, context)
            assertFalse(context.contains("�"))
            assertFalse(context.contains("изображение без описания"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingSidecarIsExplicitlyMarkedAsUnparsed() {
        val root = Files.createTempDirectory("ruri-sidecar-missing").toFile()
        try {
            FileManager.init(root)
            val image = File(root, "shelf/photo.png")
            image.parentFile?.mkdirs()
            image.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 0, 0, 0, 0))
            assertEquals(
                "[изображение без описания: vision недоступен]",
                ChatEngine.shelfText(root, "photo.png")
            )
        } finally {
            root.deleteRecursively()
        }
    }
}

class ReadUrlImageTest {
    @Test
    fun imageContentTypeUsesVisionDescriptionPath() {
        val root = Files.createTempDirectory("ruri-read-url-image").toFile()
        try {
            val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 0, 0, 0, 0)
            var called = false
            val (ok, text) = UrlReader.contentResult(
                root = root,
                mime = "image/png",
                bytes = bytes
            ) { _, actualBytes, actualMime, prompt ->
                called = true
                assertEquals(bytes.toList(), actualBytes.toList())
                assertEquals("image/png", actualMime)
                assertTrue(prompt.contains("Не выдумывай детали"))
                "На изображении красный велосипед."
            }

            assertTrue(called)
            assertTrue(ok)
            assertEquals("На изображении красный велосипед.", text)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun imageMagicIsRecognizedEvenWhenServerReturnsGenericMime() {
        val root = Files.createTempDirectory("ruri-read-url-image-mime").toFile()
        try {
            val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0)
            var usedVision = false
            val (ok, text) = UrlReader.contentResult(
                root = root,
                mime = "application/octet-stream",
                bytes = bytes
            ) { _, _, mime, _ ->
                usedVision = true
                assertEquals("image/jpeg", mime)
                "Фотография улицы."
            }
            assertTrue(usedVision)
            assertTrue(ok)
            assertEquals("Фотография улицы.", text)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingVisionRouteReturnsHonestImageFailure() {
        val root = Files.createTempDirectory("ruri-read-url-image-unavailable").toFile()
        try {
            val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 0, 0, 0, 0)
            val (ok, text) = UrlReader.contentResult(root, "image/png", bytes) { _, _, _, _ -> null }
            assertFalse(ok)
            assertEquals("Изображение получено, но vision-маршрут недоступен", text)
        } finally {
            root.deleteRecursively()
        }
    }
}
