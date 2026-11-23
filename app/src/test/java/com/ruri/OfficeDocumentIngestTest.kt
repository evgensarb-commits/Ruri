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
