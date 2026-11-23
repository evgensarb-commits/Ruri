package com.ruri

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun officeTestRoot(): File =
    Files.createTempDirectory("ruri-office-ingest").toFile().also { FileManager.init(it) }

private fun officeZip(path: String, xml: String): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        zip.putNextEntry(ZipEntry(path))
        zip.write(xml.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
    return bytes.toByteArray()
}

private fun lastShelfEvent(root: File): JSONObject =
    FileManager.readLastLines(root, "memory/L0_raw.jsonl", 100)
        .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        .last { it.optString("type") == "shelf_file_ingested" }

class DocxExtractTest {
    @Test fun extractsRealTextFromWordDocumentXmlAndLogsOfficeExtractor() {
        val root = officeTestRoot()
        try {
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>Содержимое документа DOCX</w:t></w:r></w:p>
                  <w:p><w:r><w:t>Вторая строка &amp; важная деталь</w:t></w:r></w:p></w:body>
                </w:document>""".trimIndent()
            val bytes = officeZip("word/document.xml", xml)
            assertEquals("Содержимое документа DOCX\nВторая строка & важная деталь",
                ShelfWatcher.extractOffice(bytes, "sample.docx"))
            File(root, "shelf").mkdirs()
            File(root, "shelf/sample.docx").writeBytes(bytes)
            assertTrue(ShelfWatcher.ingestFile(root, "sample.docx")!!.contains("Содержимое документа"))
            val event = lastShelfEvent(root)
            assertEquals("office_xml", event.optString("extractor"))
            assertTrue(event.optString("text").contains("Вторая строка & важная деталь"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class OdtExtractTest {
    @Test fun extractsParagraphsFromOdtContentXml() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <office:document-content
              xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
              xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
              <office:body><office:text>
                <text:p>Первый абзац ODT</text:p><text:p>Второй <text:span>важный</text:span> абзац</text:p>
              </office:text></office:body>
            </office:document-content>""".trimIndent()
        val extracted = ShelfWatcher.extractOffice(officeZip("content.xml", xml), "notes.odt")
        assertEquals("Первый абзац ODT\nВторой важный абзац", extracted)
    }
}

class TxtUnchangedTest {
    @Test fun plainUtf8TextStillUsesExistingTextDecoder() {
        val root = officeTestRoot()
        try {
            File(root, "shelf").mkdirs()
            File(root, "shelf/notes.txt").writeText("Обычный UTF-8 текст без изменений.", Charsets.UTF_8)
            assertTrue(ShelfWatcher.ingestFile(root, "notes.txt")!!.contains("Обычный UTF-8 текст"))
            val event = lastShelfEvent(root)
            assertEquals("utf8_text", event.optString("extractor"))
            assertEquals("Обычный UTF-8 текст без изменений.", event.optString("text"))
        } finally {
            root.deleteRecursively()
        }
    }
}

class DocLegacyHonestTest {
    @Test fun legacyOleDocWithNoExtractableTextIsMetadataOnly() {
        val root = officeTestRoot()
        try {
            File(root, "shelf").mkdirs()
            File(root, "shelf/old.doc").writeBytes(ByteArray(256))
            ShelfWatcher.ingestFile(root, "old.doc")
            val event = lastShelfEvent(root)
            assertEquals("metadata_only", event.optString("extractor"))
            assertTrue(event.optString("text").isBlank())
            assertFalse(event.optString("extractor") == "office_xml")
        } finally {
            root.deleteRecursively()
        }
    }
}

class PdfFragmentsHonestTest {
    @Test fun pdfOnlyGetsBestEffortReadableFragmentsNotOfficeExtraction() {
        val root = officeTestRoot()
        try {
            File(root, "shelf").mkdirs()
            val fragment = "Invoice total is 125 euros"
            File(root, "shelf/report.pdf").writeBytes(
                ("%PDF-1.4\u0000\u0001" + fragment + "\u0000%%EOF").toByteArray(Charsets.UTF_8)
            )
            ShelfWatcher.ingestFile(root, "report.pdf")
            val event = lastShelfEvent(root)
            assertTrue(event.optString("text").contains(fragment))
            assertEquals("utf8_text", event.optString("extractor"))
            assertFalse(event.optString("extractor") == "office_xml")
        } finally {
            root.deleteRecursively()
        }
    }
}


class ReadShelfDocxFullTest {
    @Test fun returnsWholeDocxAcrossSequentialChunks() {
        val root = officeTestRoot()
        try {
            val text = (1..1800).joinToString(" ") { "Слово$it." }
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body>
                </w:document>""".trimIndent()
            File(root, "shelf").mkdirs()
            val bytes = officeZip("word/document.xml", xml)
            File(root, "shelf/full.docx").writeBytes(bytes)
            val extracted = ShelfWatcher.extractOffice(bytes, "full.docx")
            val rebuilt = StringBuilder()
            var offset = 0
            while (offset < extracted.length) {
                val chunk = ChatEngine.shelfText(root, "full.docx", offset)
                val headerEnd = chunk.indexOf(" из ").let { chunk.indexOf("\\n", it) + 1 }
                val bodyStart = headerEnd
                val bodyEnd = chunk.indexOf("\\n[", bodyStart).let { if (it < 0) chunk.length else it }
                rebuilt.append(chunk.substring(bodyStart, bodyEnd))
                offset = minOf(offset + 7000, extracted.length)
            }
            assertEquals(extracted, rebuilt.toString())
            assertTrue(ChatEngine.shelfText(root, "full.docx", extracted.length).contains("прочитан полностью"))
        } finally { root.deleteRecursively() }
    }
}

class ReadShelfOdtFullTest {
    @Test fun extractsOdtXmlInsteadOfZipBytes() {
        val root = officeTestRoot()
        try {
            File(root, "shelf").mkdirs()
            val xml = """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:text>
                <text:p>ODT first paragraph.</text:p><text:p>ODT final sentence.</text:p>
                </office:text></office:body></office:document-content>"""
            File(root, "shelf/file.odt").writeBytes(officeZip("content.xml", xml))
            val result = ChatEngine.shelfText(root, "file.odt")
            assertTrue(result.contains("ODT first paragraph."))
            assertTrue(result.contains("ODT final sentence."))
            assertFalse(result.contains("PK"))
        } finally { root.deleteRecursively() }
    }
}

class ReadShelfTxtFullTest {
    @Test fun readsPlainTextThroughFinalCharacter() {
        val root = officeTestRoot()
        try {
            File(root, "shelf").mkdirs()
            File(root, "shelf/a.txt").writeText("Первая буква. Последняя точка.", Charsets.UTF_8)
            val result = ChatEngine.shelfText(root, "a.txt")
            assertTrue(result.contains("Первая буква. Последняя точка."))
            assertTrue(result.contains("КОНЕЦ ФАЙЛА"))
        } finally { root.deleteRecursively() }
    }
}

class ReadShelfImageDescTest {
    @Test fun readsVisionDescriptionInsteadOfBinaryImage() {
        val root = officeTestRoot()
        try {
            File(root, "shelf").mkdirs()
            File(root, "shelf/.desc").mkdirs()
            File(root, "shelf/picture.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00))
            File(root, "shelf/.desc/picture.png.txt").writeText("Описание изображения")
            assertTrue(ChatEngine.shelfText(root, "picture.png").contains("Описание изображения"))
        } finally { root.deleteRecursively() }
    }
}

class ReadShelfMissingHonestTest {
    @Test fun missingFileIsReportedHonestly() {
        val root = officeTestRoot()
        try { assertEquals("Файл не найден", ChatEngine.shelfText(root, "missing.docx")) }
        finally { root.deleteRecursively() }
    }
}

class ListShelfKindsTest {
    @Test fun listsFileNameSizeAndKind() {
        val root = officeTestRoot()
        try {
            File(root, "shelf").mkdirs()
            File(root, "shelf/a.txt").writeText("text")
            val result = ChatEngine.executeTool(root, "list_shelf", JSONObject())
            assertTrue(result.first)
            assertTrue(result.second.contains("a.txt"))
            assertTrue(result.second.contains("байт"))
            assertTrue(result.second.contains("text"))
        } finally { root.deleteRecursively() }
    }
}
