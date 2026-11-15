package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.lab.LabData
import com.chencohensolution.transcriptor.notebook.Label
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.notebook.SearchHit
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Speaker
import com.chencohensolution.transcriptor.notebook.searchMoments
import com.chencohensolution.transcriptor.notebook.summary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Telling moments apart in the list (who, and a first sentence) and finding them by what was said. */
class SearchTest {
    private fun moment(id: String, title: String, at: Long, vararg said: Pair<String?, String>, speakers: List<Speaker> = emptyList(), notes: List<Note> = emptyList(), labels: List<String> = emptyList()) =
        Lecture(
            id, title, "en", at, 60_000,
            segments = said.mapIndexed { i, (who, text) -> Segment("s${i + 1}", i * 5_000L, i * 5_000L + 4_000, text, "m", speakerId = who) },
            notes = notes, speakers = speakers, labels = labels,
        )

    private val dinner = moment(
        "dinner", "Nov 14, 2026", 3_000,
        "speaker-2" to "okay", "speaker-2" to "no, the second weekend, the first one they're fully booked",
        "speaker-3" to "the guy said he'd throw in the boat", "speaker-1" to "Dan, tell them about the cabin guy and the price he gave us for both nights, because that was the strange part of the call",
        speakers = listOf(Speaker("speaker-1", "Hen"), Speaker("speaker-2", "Maya"), Speaker("speaker-3", "")),
        notes = listOf(Note("n1", 12_000, "ask about the boat")), labels = listOf("trips"),
    )
    private val trip = moment(
        "trip", "Trip planning", 5_000,
        "speaker-1" to "so we booked the cabin for the second weekend", "speaker-1" to "the cabin has a stove",
        speakers = listOf(Speaker("speaker-1", "Hen")),
    )
    private val call = moment("call", "Call with the landlord", 1_000, null to "the boiler again")
    private val labels = listOf(Label("trips", "Trips"), Label("home", "Home"))
    private val all = listOf(dinner, trip, call)

    @Test
    fun aRowSaysWhoWasThereAndHowItStarted() {
        val row = dinner.summary()
        // Maya talked most; the unnamed one is counted, not named.
        assertEquals(listOf("Hen", "Maya").sorted(), row.people.sorted())
        assertEquals(1, row.unnamedPeople)
        // "okay" says little: the first line of five words or more.
        assertEquals("no, the second weekend, the first one they're fully booked", row.preview)
        // No speakers told apart, nothing said: no people, the only line as it is.
        assertEquals(emptyList(), call.summary().people)
        assertEquals("the boiler again", call.summary().preview)
        assertEquals("", moment("empty", "t", 0).summary().preview)
        // A long first sentence is cut at a word.
        val long = moment("long", "t", 0, null to dinner.segments[3].text).summary().preview
        assertTrue(long.endsWith("…") && long.length <= 111 && !long.contains("  "), long)
        // The Hebrew lecture of the lab still summarizes.
        assertTrue(LabData.liveLectureWithSpeakers.summary().preview.isNotEmpty())
    }

    @Test
    fun whatWasSaidIsFoundNewestFirstWithTheLineToOpenAt() {
        val hits = searchMoments(all, labels, "Cabin")
        assertEquals(listOf("trip", "dinner"), hits.map { it.moment.id })
        val first = hits[0]
        assertEquals(SearchHit.Where.Line, first.where)
        assertEquals(0L, first.atMillis)
        assertEquals("Hen", first.speaker)
        assertEquals(1, first.more)
        assertEquals("cabin", first.snippet.substring(first.matchStart, first.matchEnd))
        // A long line is shown around the match.
        val second = hits[1]
        assertEquals(15_000L, second.atMillis)
        assertTrue(second.snippet.startsWith("…") || second.snippet.startsWith("Dan"), second.snippet)
        assertTrue(second.snippet.endsWith("…"), second.snippet)
        assertEquals("cabin", second.snippet.substring(second.matchStart, second.matchEnd))
    }

    @Test
    fun titlesPeopleLabelsAndNotesAreFoundToo() {
        assertEquals(listOf(SearchHit.Where.Title), searchMoments(all, labels, "landlord").map { it.where })
        val maya = searchMoments(all, labels, "maya").single()
        assertEquals(SearchHit.Where.Person to "dinner", maya.where to maya.moment.id)
        assertEquals(null, maya.atMillis)
        val byLabel = searchMoments(all, labels, "trips").single()
        assertEquals(SearchHit.Where.Label, byLabel.where)
        // "Trip" is a title, and a label of another moment.
        assertEquals(listOf("trip" to SearchHit.Where.Title, "dinner" to SearchHit.Where.Label), searchMoments(all, labels, "trip").map { it.moment.id to it.where })
        // The boat is said at 0:10 and noted at 0:12: the earlier place, and one more.
        val boat = searchMoments(all, labels, "boat").single()
        assertEquals(SearchHit.Where.Line, boat.where)
        assertEquals(10_000L, boat.atMillis)
        assertEquals(1, boat.more)
        assertEquals(SearchHit.Where.Note, searchMoments(all, labels, "ask about").single().where)
    }

    @Test
    fun aShortOrUnknownQueryFindsNothing() {
        assertEquals(emptyList(), searchMoments(all, labels, " c "))
        assertEquals(emptyList(), searchMoments(all, labels, "submarine"))
    }
}
