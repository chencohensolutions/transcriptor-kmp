package com.chencohensolution.transcriptor.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.notebook.Lecture
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A real Word file of a lecture, for the round's export options (lab only, deleted when the round closes; the app's
 * would be in sharedLogic with tests). The title, a line with the notebook, the date and the length, then a paragraph
 * per speaker turn: the speaker's name in bold and its time in grey above it, when chosen, and the notes, when
 * chosen, as shaded italic paragraphs where they were written. A Hebrew lecture's paragraphs are right-to-left.
 */
internal object LabWord {
    const val MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    fun bytes(context: Context, lecture: Lecture, notebookTitle: String, speakers: Boolean, times: Boolean, notes: Boolean): ByteArray {
        val rtl = lecture.language in setOf("he", "iw", "ar", "fa", "ur", "yi")
        val body = StringBuilder()
        fun para(text: String, bold: Boolean = false, italic: Boolean = false, size: Int? = null, color: String? = null, shade: String? = null, after: Int = 120) {
            body.append("<w:p><w:pPr>")
            if (rtl) body.append("<w:bidi/>")
            if (shade != null) body.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$shade\"/>")
            body.append("<w:spacing w:after=\"$after\"/></w:pPr><w:r><w:rPr>")
            if (bold) body.append("<w:b/><w:bCs/>")
            if (italic) body.append("<w:i/><w:iCs/>")
            if (color != null) body.append("<w:color w:val=\"$color\"/>")
            if (size != null) body.append("<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/>")
            if (rtl) body.append("<w:rtl/>")
            body.append("</w:rPr><w:t xml:space=\"preserve\">").append(escape(text)).append("</w:t></w:r></w:p>")
        }
        para(lecture.title, bold = true, size = 40, after = 60)
        val minutes = (lecture.durationMillis / 60_000).toInt()
        para(
            listOf(notebookTitle, DateFormat.getDateInstance(DateFormat.LONG).format(Date(lecture.startedAtMillis)), "$minutes min").joinToString(" · "),
            color = "777777", after = 360,
        )
        val turns = mutableListOf<MutableList<com.chencohensolution.transcriptor.notebook.Segment>>()
        for (s in lecture.segments) {
            if (turns.isEmpty() || turns.last().last().speakerId != s.speakerId) turns.add(mutableListOf(s)) else turns.last().add(s)
        }
        val pending = lecture.notes.sortedBy { it.atMillis }.toMutableList()
        for (turn in turns) {
            val heading = listOfNotNull(
                if (speakers) turn.first().speakerId?.let { id ->
                    lecture.speakers.firstOrNull { it.id == id }?.name?.ifEmpty { null }
                        ?: context.getString(R.string.speaker_n, id.removePrefix("speaker-").toIntOrNull() ?: 0)
                } else null,
                if (times) formatClock(turn.first().startMillis) else null,
            )
            if (heading.isNotEmpty()) para(heading.joinToString("   "), bold = speakers, color = if (speakers) null else "777777", after = 40)
            para(turn.joinToString(" ") { it.text }, after = 200)
            if (notes) {
                val end = turn.last().endMillis
                while (pending.isNotEmpty() && pending.first().atMillis <= end) {
                    val n = pending.removeAt(0)
                    para("📝 ${formatClock(n.atMillis)}  ${n.text}", italic = true, shade = "FFF4D6", after = 200)
                }
            }
        }
        if (notes) pending.forEach { para("📝 ${formatClock(it.atMillis)}  ${it.text}", italic = true, shade = "FFF4D6", after = 200) }

        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/>${if (rtl) "<w:bidi/>" else ""}</w:sectPr></w:body></w:document>"""
        val types = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in listOf("[Content_Types].xml" to types, "_rels/.rels" to rels, "word/document.xml" to document)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Saves it in Downloads/Transcriptor (the lab can't reach the lectures folder) and returns where. */
    fun save(context: Context, fileName: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Transcriptor")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        return uri
    }

    /** Android's share sheet with the file (Word, Drive, mail, WhatsApp…). */
    fun share(context: Context, uri: Uri, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
