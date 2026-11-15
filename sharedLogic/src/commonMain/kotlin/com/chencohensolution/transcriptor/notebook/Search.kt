package com.chencohensolution.transcriptor.notebook

/**
 * A moment a search found, with where: in what was said ([Where.Line], at [atMillis], said by [speaker]), in a note,
 * or in its title, a speaker's name or a label. [snippet] is the text around the match, the match itself from
 * [matchStart] until [matchEnd] in it. [more] is how many other lines and notes of the moment match too.
 */
data class SearchHit(
    val moment: LectureSummary,
    val where: Where,
    val snippet: String,
    val matchStart: Int,
    val matchEnd: Int,
    /** Where to open the moment: the line's or note's time; null for a match in its title, people or labels. */
    val atMillis: Long? = null,
    /** The name of who said the line, when they have one. */
    val speaker: String = "",
    val more: Int = 0,
) {
    enum class Where { Title, Person, Label, Note, Line }
}

/**
 * The moments [query] is found in, newest first, one hit a moment: its title first, then who was in it, its labels,
 * and what was said or noted (the first such place, with how many more). Letters match whatever their case; a query
 * of several words is looked for as written. Fewer than [MIN_QUERY] characters find nothing.
 */
fun searchMoments(lectures: List<Lecture>, labels: List<Label>, query: String): List<SearchHit> {
    val q = query.trim()
    if (q.length < MIN_QUERY) return emptyList()
    val names = labels.associate { it.id to it.name }
    return lectures.sortedByDescending { it.startedAtMillis }.mapNotNull { l ->
        val summary = l.summary()
        fun hit(where: SearchHit.Where, text: String, at: Long? = null, speaker: String = "", more: Int = 0): SearchHit? {
            val i = text.indexOf(q, ignoreCase = true)
            if (i < 0) return null
            val (snippet, start) = around(text, i, q.length)
            return SearchHit(summary, where, snippet, start, start + q.length, at, speaker, more)
        }
        val said = l.segments.filter { it.text.contains(q, ignoreCase = true) }
        val noted = l.notes.filter { it.text.contains(q, ignoreCase = true) }
        val inside = said.size + noted.size
        hit(SearchHit.Where.Title, l.title, more = inside)
            ?: l.speakers.firstNotNullOfOrNull { hit(SearchHit.Where.Person, it.name, more = inside) }
            ?: l.labels.firstNotNullOfOrNull { id -> names[id]?.let { hit(SearchHit.Where.Label, it, more = inside) } }
            ?: listOfNotNull(
                said.firstOrNull()?.let { s -> hit(SearchHit.Where.Line, s.text, s.startMillis, l.speakers.firstOrNull { it.id == s.speakerId }?.name.orEmpty(), inside - 1) },
                noted.firstOrNull()?.let { n -> hit(SearchHit.Where.Note, n.text, n.atMillis, more = inside - 1) },
            ).minByOrNull { it.atMillis ?: 0 }
    }
}

/** [text] around a match at [at] of [length] characters: at most [SNIPPET_BEFORE] before it, cut at a word, and the match's start in it. */
private fun around(text: String, at: Int, length: Int): Pair<String, Int> {
    var from = (at - SNIPPET_BEFORE).coerceAtLeast(0)
    if (from > 0) from = text.indexOf(' ', from).takeIf { it in 0 until at }?.plus(1) ?: from
    var to = (at + length + SNIPPET_AFTER).coerceAtMost(text.length)
    if (to < text.length) to = text.lastIndexOf(' ', to).takeIf { it > at + length } ?: to
    val head = if (from > 0) "…" else ""
    val tail = if (to < text.length) "…" else ""
    return (head + text.substring(from, to) + tail) to (head.length + at - from)
}

const val MIN_QUERY = 2
private const val SNIPPET_BEFORE = 36
private const val SNIPPET_AFTER = 70
