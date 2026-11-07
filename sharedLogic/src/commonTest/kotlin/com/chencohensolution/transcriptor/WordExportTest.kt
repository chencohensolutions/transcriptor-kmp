package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.lab.LabData
import com.chencohensolution.transcriptor.notebook.crc32
import com.chencohensolution.transcriptor.notebook.docxName
import com.chencohensolution.transcriptor.notebook.storedZip
import com.chencohensolution.transcriptor.notebook.transcriptDocumentXml
import com.chencohensolution.transcriptor.notebook.transcriptDocx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordExportTest {
    private val lecture = LabData.refinedLecture

    @Test
    fun aHebrewLectureReadsRightToLeftWithItsSpeakersTimesAndNotesInOrder() {
        val xml = transcriptDocumentXml(lecture, "מבוא לפסיכולוגיה", utcOffsetMillis = 2 * 3_600_000L)
        assertTrue("<w:bidi/>" in xml && "<w:rtl/>" in xml)
        assertTrue("מבוא לפסיכולוגיה · 2026-11-02 10:00 · 47:12" in xml)
        // Turns: the lecturer, the student's question, the lecturer again; each name with its turn's time.
        val heads = Regex("""<w:b/><w:bCs/><w:rtl/></w:rPr><w:t xml:space="preserve">([^<]*)</w:t>""").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(listOf("המרצה   0:02", "דובר 2   1:37", "המרצה   1:44"), heads)
        // A note comes after the turn it was written in, before the next one.
        val note = xml.indexOf("📝 0:33  פסיבי")
        assertTrue(note > xml.indexOf("הבעיה המרכזית") && note < xml.indexOf("דובר 2   1:37"))
        assertTrue(xml.indexOf("📝 2:53") > xml.indexOf("דיכוי ארטיקולטורי. תכתבו"))
    }

    @Test
    fun anEnglishLectureWithoutSpeakersHasLeftToRightParagraphsAndGreyTimes() {
        val english = lecture.copy(language = "en", title = "Reading <abstracts> & papers", speakers = emptyList(), segments = lecture.segments.map { it.copy(speakerId = null) })
        val xml = transcriptDocumentXml(english, null)
        assertFalse("<w:bidi/>" in xml)
        assertTrue("Reading &lt;abstracts&gt; &amp; papers" in xml)
        assertTrue("""<w:color w:val="777777"/></w:rPr><w:t xml:space="preserve">0:02</w:t>""" in xml)
        assertEquals("Reading _abstracts_ & papers.docx".replace("_", " "), docxName(english))
    }

    @Test
    fun theFileIsAZipWithTheThreePartsWordNeeds() {
        assertEquals(0xCBF43926L, crc32("123456789".encodeToByteArray()))
        val zip = transcriptDocx(lecture, "Psych")
        assertEquals(listOf<Byte>(0x50, 0x4B, 0x03, 0x04), zip.take(4))
        val text = zip.decodeToString()
        for (part in listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml")) assertTrue(part in text, part)
        // The end of the central directory: three entries.
        val end = zip.size - 22
        assertEquals(listOf<Byte>(0x50, 0x4B, 0x05, 0x06), zip.slice(end until end + 4))
        assertEquals(3, zip[end + 10].toInt())
        assertEquals(storedZip(emptyList()).size, 22)
    }

    @Test
    fun savingPutsTheWordFileBesideTheRecording() {
        val fs = okio.fakefilesystem.FakeFileSystem()
        val store = com.chencohensolution.transcriptor.notebook.LectureStore(fs, okio.Path.Companion.run { "/files".toPath() })
        store.save(lecture)
        assertEquals("זיכרון עבודה.docx", store.saveWord(lecture, "Psych"))
        val file = store.folder(lecture.notebookId, lecture.id) / "זיכרון עבודה.docx"
        assertTrue(transcriptDocx(lecture, "Psych").contentEquals(fs.read(file) { readByteArray() }))
        assertFalse(fs.exists(store.folder(lecture.notebookId, lecture.id) / "זיכרון עבודה.docx.tmp"))
    }
}
