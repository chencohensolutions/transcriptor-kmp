package com.chencohensolution.transcriptor.review

import com.chencohensolution.transcriptor.notebook.Segment

/** A word as Compare shows it: [changed] when the other transcript doesn't have it there. */
data class ComparedWord(val text: String, val changed: Boolean)

/**
 * One stretch of the recording in two transcripts: the line now ([now]) and what the other transcript wrote over the
 * same time ([other]), word by word. [differs] when any word differs.
 */
data class ComparedLine(val startMillis: Long, val now: List<ComparedWord>, val other: List<ComparedWord>) {
    val differs get() = now.any { it.changed } || other.any { it.changed }
}

/**
 * The transcript now beside another one (an earlier version), a line of [now] at a time: the other's lines are given to
 * the line of [now] they overlap most in time (a pass may cut the lines elsewhere), and the words are matched in order
 * (longest common run), ignoring punctuation and case, so a word one of them misheard, added or dropped is marked.
 * The other's lines that overlap none of [now] come as lines of their own.
 */
fun compareTranscripts(now: List<Segment>, other: List<Segment>): List<ComparedLine> {
    val given = other.groupBy { o ->
        now.withIndex().maxByOrNull { (_, n) -> overlap(n, o) }?.takeIf { (_, n) -> overlap(n, o) > 0 }?.index
    }
    val lines = now.mapIndexed { i, n ->
        val theirs = given[i].orEmpty().joinToString(" ") { it.text }
        val (a, b) = compareWords(words(n.text), words(theirs))
        ComparedLine(n.startMillis, a, b)
    }
    val alone = given[null].orEmpty().map { o -> ComparedLine(o.startMillis, emptyList(), words(o.text).map { ComparedWord(it, true) }) }
    return (lines + alone).sortedBy { it.startMillis }
}

private fun overlap(a: Segment, b: Segment) = minOf(a.endMillis, b.endMillis) - maxOf(a.startMillis, b.startMillis)

private fun words(text: String) = text.split(' ', '\n', '\t').filter { it.isNotBlank() }

private fun key(word: String) = word.filter { it.isLetterOrDigit() }.lowercase()

/** Both word lists, each word marked changed unless it is in their longest common run (by [key]). */
internal fun compareWords(a: List<String>, b: List<String>): Pair<List<ComparedWord>, List<ComparedWord>> {
    val ka = a.map(::key)
    val kb = b.map(::key)
    val n = ka.size
    val m = kb.size
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
        lcs[i][j] = if (ka[i] == kb[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    }
    val sameA = BooleanArray(n)
    val sameB = BooleanArray(m)
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            ka[i] == kb[j] -> { sameA[i] = true; sameB[j] = true; i++; j++ }
            lcs[i + 1][j] >= lcs[i][j + 1] -> i++
            else -> j++
        }
    }
    // A word of punctuation only (a dash) is never a difference.
    return a.mapIndexed { x, w -> ComparedWord(w, !sameA[x] && ka[x].isNotEmpty()) } to b.mapIndexed { x, w -> ComparedWord(w, !sameB[x] && kb[x].isNotEmpty()) }
}
