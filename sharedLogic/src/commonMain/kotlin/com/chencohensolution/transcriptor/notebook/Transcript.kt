package com.chencohensolution.transcriptor.notebook

/**
 * The lecture as a document to read beside its audio (`transcript.md`): its title, date and length, then each
 * speaker's turn (consecutive lines of one speaker) with its time, and the notes after the turn they were written in.
 * Speakers without a name are "Speaker 2", in the lecture's language when [SPEAKER] has it ("דובר 2", "Sprecher 2").
 * [utcOffsetMillis] is the phone's, for the date.
 */
fun transcriptMarkdown(lecture: Lecture, utcOffsetMillis: Long = 0): String {
    val speaker = SPEAKER[com.chencohensolution.transcriptor.models.Languages.normalize(lecture.language)] ?: "Speaker"
    val names = lecture.speakers.mapIndexed { i, s -> s.id to s.name.ifBlank { "$speaker ${i + 1}" } }.toMap()
    val out = StringBuilder()
    out.append("# ").append(escape(lecture.title)).append("\n\n")
    out.append(dateOf(lecture.startedAtMillis + utcOffsetMillis)).append(" · ").append(clock(lecture.durationMillis)).append("\n")

    val turns = mutableListOf<MutableList<Segment>>()
    for (line in lecture.segments.sortedBy { it.startMillis }) {
        val last = turns.lastOrNull()
        if (last != null && last.last().speakerId == line.speakerId) last.add(line) else turns.add(mutableListOf(line))
    }
    val notes = lecture.notes.sortedBy { it.atMillis }.toMutableList()
    fun notesBefore(millis: Long) {
        while (notes.isNotEmpty() && notes.first().atMillis < millis) {
            val note = notes.removeAt(0)
            out.append("\n> 📝 ").append(clock(note.atMillis)).append(" — ").append(escape(note.text).replace("\n", "\n> ")).append("\n")
        }
    }
    turns.forEachIndexed { i, turn ->
        if (i == 0) notesBefore(turn.first().startMillis)
        out.append("\n")
        turn.first().speakerId?.let { names[it] }?.let { out.append("**").append(escape(it)).append("** · ") }
        out.append(clock(turn.first().startMillis)).append("\n")
        out.append(turn.joinToString(" ") { escape(it.text.trim()) }).append("\n")
        notesBefore(turns.getOrNull(i + 1)?.first()?.startMillis ?: Long.MAX_VALUE)
    }
    notesBefore(Long.MAX_VALUE)
    return out.toString()
}

/** "Speaker" in the languages most lectures are in; English for the rest. */
private val SPEAKER = mapOf(
    "he" to "דובר", "ar" to "متحدث", "en" to "Speaker", "de" to "Sprecher", "fr" to "Intervenant", "es" to "Hablante",
    "it" to "Relatore", "pt" to "Orador", "ru" to "Говорящий", "uk" to "Мовець", "pl" to "Mówca", "nl" to "Spreker",
    "tr" to "Konuşmacı", "fa" to "گوینده", "hi" to "वक्ता", "zh" to "发言人", "ja" to "話者", "ko" to "화자",
)

/** Markdown's own characters, escaped so the text reads as written. */
private fun escape(text: String) = text.replace(Regex("""([\\`*_\[\]#<>|])"""), """\\$1""")

/** 12:03, or 1:02:03 past an hour. */
private fun clock(millis: Long): String {
    val total = millis / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
}

private fun two(n: Long) = n.toString().padStart(2, '0')

/** "2026-11-02 10:00" from local milliseconds (UTC plus the offset). */
private fun dateOf(localMillis: Long): String {
    val days = localMillis.floorDiv(DAY)
    val minutes = localMillis.mod(DAY) / 60_000
    val (y, m, d) = civil(days)
    return "$y-${two(m.toLong())}-${two(d.toLong())} ${two(minutes / 60)}:${two(minutes % 60)}"
}

private const val DAY = 24 * 60 * 60_000L

/** The date of a day counted from 1970-01-01 (Howard Hinnant's civil_from_days). */
internal fun civil(days: Long): Triple<Int, Int, Int> {
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    return Triple((yoe + era * 400 + if (m <= 2) 1 else 0).toInt(), m, d)
}
