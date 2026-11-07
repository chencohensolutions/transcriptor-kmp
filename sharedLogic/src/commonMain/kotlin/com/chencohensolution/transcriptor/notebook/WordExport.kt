package com.chencohensolution.transcriptor.notebook

import com.chencohensolution.transcriptor.models.Languages

/**
 * The lecture as a Word file (.docx) to share or keep beside its audio: its title, a line with the notebook, the date
 * and the length, then a paragraph per speaker's turn with the speaker's name in bold and the turn's time in grey above
 * it, and the notes, as shaded italic paragraphs, after the turn they were written in (the same reading as
 * [transcriptMarkdown]). A right-to-left lecture (Hebrew, Arabic…) has right-to-left paragraphs. [utcOffsetMillis] is
 * the phone's, for the date.
 */
fun transcriptDocx(lecture: Lecture, notebookTitle: String?, utcOffsetMillis: Long = 0): ByteArray =
    storedZip(
        listOf(
            "[Content_Types].xml" to CONTENT_TYPES,
            "_rels/.rels" to RELS,
            "word/document.xml" to transcriptDocumentXml(lecture, notebookTitle, utcOffsetMillis),
        ).map { (name, text) -> name to text.encodeToByteArray() },
    )

/** The file's name for a lecture: its title, without the characters file systems refuse. */
fun docxName(lecture: Lecture): String = lecture.title.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").trim().ifEmpty { lecture.id } + ".docx"

internal fun transcriptDocumentXml(lecture: Lecture, notebookTitle: String?, utcOffsetMillis: Long = 0): String {
    val language = Languages.normalize(lecture.language)
    val rtl = language in RTL
    val speaker = SPEAKER[language] ?: "Speaker"
    val names = lecture.speakers.mapIndexed { i, s -> s.id to s.name.ifBlank { "$speaker ${i + 1}" } }.toMap()
    val body = StringBuilder()
    fun paragraph(text: String, bold: Boolean = false, italic: Boolean = false, size: Int? = null, color: String? = null, shade: String? = null, after: Int = 120) {
        body.append("<w:p><w:pPr>")
        if (rtl) body.append("<w:bidi/>")
        if (shade != null) body.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$shade\"/>")
        body.append("<w:spacing w:after=\"$after\"/></w:pPr><w:r><w:rPr>")
        if (bold) body.append("<w:b/><w:bCs/>")
        if (italic) body.append("<w:i/><w:iCs/>")
        if (color != null) body.append("<w:color w:val=\"$color\"/>")
        if (size != null) body.append("<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/>")
        if (rtl) body.append("<w:rtl/>")
        body.append("</w:rPr><w:t xml:space=\"preserve\">").append(xml(text)).append("</w:t></w:r></w:p>")
    }
    paragraph(lecture.title, bold = true, size = 40, after = 60)
    paragraph(
        listOfNotNull(notebookTitle, dateOf(lecture.startedAtMillis + utcOffsetMillis), clock(lecture.durationMillis)).joinToString(" · "),
        color = GREY, after = 360,
    )
    val turns = mutableListOf<MutableList<Segment>>()
    for (line in lecture.segments.sortedBy { it.startMillis }) {
        val last = turns.lastOrNull()
        if (last != null && last.last().speakerId == line.speakerId) last.add(line) else turns.add(mutableListOf(line))
    }
    val notes = lecture.notes.sortedBy { it.atMillis }.toMutableList()
    fun notesBefore(millis: Long) {
        while (notes.isNotEmpty() && notes.first().atMillis < millis) {
            val note = notes.removeAt(0)
            paragraph("📝 ${clock(note.atMillis)}  ${note.text.trim()}", italic = true, shade = NOTE, after = 200)
        }
    }
    turns.forEachIndexed { i, turn ->
        if (i == 0) notesBefore(turn.first().startMillis)
        val name = turn.first().speakerId?.let { names[it] }
        if (name != null) paragraph("$name   ${clock(turn.first().startMillis)}", bold = true, after = 40)
        else paragraph(clock(turn.first().startMillis), color = GREY, after = 40)
        paragraph(turn.joinToString(" ") { it.text.trim() }, after = 200)
        notesBefore(turns.getOrNull(i + 1)?.first()?.startMillis ?: Long.MAX_VALUE)
    }
    notesBefore(Long.MAX_VALUE)
    return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/>${if (rtl) "<w:bidi/>" else ""}</w:sectPr></w:body></w:document>"""
}

/** Languages written right to left. */
private val RTL = setOf("he", "ar", "fa", "ur", "yi")
private const val GREY = "777777"
private const val NOTE = "FFF4D6"

private fun xml(text: String) = buildString {
    for (c in text) when {
        c == '&' -> append("&amp;")
        c == '<' -> append("&lt;")
        c == '>' -> append("&gt;")
        // Control characters XML refuses.
        c < ' ' && c != '\t' -> append(' ')
        else -> append(c)
    }
}

private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""

private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

/** A zip of [entries], stored (not compressed): all a .docx needs, in common code. */
internal fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
    val out = Bytes()
    val central = Bytes()
    for ((name, data) in entries) {
        val nameBytes = name.encodeToByteArray()
        val crc = crc32(data)
        val offset = out.size
        // Local file header: version 2.0, UTF-8 names, stored, 1980-01-01 00:00.
        out.int(0x04034b50); out.short(20); out.short(0x0800); out.short(0); out.short(0); out.short(0x21)
        out.int(crc.toInt()); out.int(data.size); out.int(data.size); out.short(nameBytes.size); out.short(0)
        out.bytes(nameBytes); out.bytes(data)
        central.int(0x02014b50); central.short(20); central.short(20); central.short(0x0800); central.short(0); central.short(0); central.short(0x21)
        central.int(crc.toInt()); central.int(data.size); central.int(data.size); central.short(nameBytes.size); central.short(0); central.short(0)
        central.short(0); central.short(0); central.int(0); central.int(offset); central.bytes(nameBytes)
    }
    val centralAt = out.size
    out.bytes(central.toByteArray())
    out.int(0x06054b50); out.short(0); out.short(0); out.short(entries.size); out.short(entries.size)
    out.int(central.size); out.int(centralAt); out.short(0)
    return out.toByteArray()
}

internal fun crc32(data: ByteArray): Long {
    var crc = 0xFFFFFFFFL
    for (b in data) crc = CRC_TABLE[((crc xor b.toLong()) and 0xFF).toInt()] xor (crc ushr 8)
    return crc xor 0xFFFFFFFFL
}

private val CRC_TABLE = LongArray(256) { n ->
    var c = n.toLong()
    repeat(8) { c = if (c and 1L != 0L) 0xEDB88320L xor (c ushr 1) else c ushr 1 }
    c
}

/** Little-endian bytes. */
private class Bytes {
    private var buffer = ByteArray(4096)
    var size = 0
        private set

    private fun room(n: Int) {
        if (size + n > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + n))
    }

    fun short(v: Int) { room(2); buffer[size++] = v.toByte(); buffer[size++] = (v shr 8).toByte() }
    fun int(v: Int) { short(v and 0xFFFF); short(v ushr 16) }
    fun bytes(b: ByteArray) { room(b.size); b.copyInto(buffer, size); size += b.size }
    fun toByteArray() = buffer.copyOf(size)
}
