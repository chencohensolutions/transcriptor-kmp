package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Speaker
import com.chencohensolution.transcriptor.notebook.VoiceStore
import com.chencohensolution.transcriptor.review.AudioPlayer
import com.chencohensolution.transcriptor.review.LectureReview
import com.chencohensolution.transcriptor.review.mergingSpeakers
import com.chencohensolution.transcriptor.review.onlyOf
import com.chencohensolution.transcriptor.review.peopleOf
import com.chencohensolution.transcriptor.speech.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Who was here?": a moment's speakers with how much they talked, naming them, and merging two into one. */
class PeopleTest {
    private class Player : AudioPlayer {
        override val positionMillis = MutableStateFlow(0L)
        override val playing = MutableStateFlow(false)
        override fun load(file: Path) {}
        override fun load(stream: com.chencohensolution.transcriptor.review.AudioStream) {}
        override fun play() { playing.value = true }
        override fun pause() { playing.value = false }
        override fun seekTo(millis: Long) { positionMillis.value = millis }
        override fun setSpeed(speed: Float) {}
        override fun release() {}
    }

    private fun line(id: String, start: Long, end: Long, speaker: String?) = Segment(id, start, end, "text $id", "m", speakerId = speaker)

    /** Four voices at a table: Hen (known), two unnamed, and a sliver that is really the second one. */
    private val dinner = Lecture(
        "dinner", "Sat dinner", "en", 0, 100_000,
        segments = listOf(
            line("s1", 0, 20_000, "speaker-1"), line("s2", 20_000, 30_000, "speaker-2"), line("s3", 30_000, 48_000, "speaker-3"),
            line("s4", 48_000, 49_000, "speaker-4"), line("s5", 49_000, 75_000, "speaker-2"), line("s6", 75_000, 95_000, "speaker-1"),
            line("s7", 95_000, 96_000, null),
        ),
        notes = emptyList(),
        speakers = listOf(Speaker("speaker-1", "Hen"), Speaker("speaker-2", ""), Speaker("speaker-3", ""), Speaker("speaker-4", ""), Speaker("speaker-5", "")),
    )

    @Test
    fun peopleAreListedByHowMuchTheyTalkedWithASampleToHear() {
        val people = peopleOf(dinner)
        // The speaker with no lines isn't listed, nor the line nobody was given.
        assertEquals(listOf("speaker-1", "speaker-2", "speaker-3", "speaker-4"), people.map { it.speakerId })
        assertEquals(listOf(40_000L, 36_000L, 18_000L, 1_000L), people.map { it.talkMillis })
        assertEquals(1f, people.sumOf { it.share.toDouble() }.toFloat(), 0.001f)
        assertEquals("Hen", people[0].name)
        // The sample is their longest line.
        assertEquals(49_000L to 75_000L, people[1].sampleStartMillis to people[1].sampleEndMillis)
        assertEquals(listOf(false, false, false, true), people.map { it.minor })
    }

    @Test
    fun mergingGivesTheLinesToTheOtherAndDropsTheSpeaker() {
        val merged = dinner.mergingSpeakers("speaker-4", "speaker-2")
        assertEquals("speaker-2", merged.segments.first { it.id == "s4" }.speakerId)
        assertFalse(merged.speakers.any { it.id == "speaker-4" })
        assertEquals(listOf(40_000L, 37_000L, 18_000L), peopleOf(merged).map { it.talkMillis })
        // Not a speaker of the moment, or the same one: nothing changes.
        assertEquals(dinner, dinner.mergingSpeakers("speaker-9", "speaker-2"))
        assertEquals(dinner, dinner.mergingSpeakers("speaker-2", "speaker-2"))
    }

    private val fs = FakeFileSystem()
    private val root = "/data".toPath()
    private val store = LectureStore(fs, root)
    private val voices = VoiceStore(fs, root)
    private val player = Player()

    private fun TestScope.review(): LectureReview {
        store.save(dinner)
        voices.save(Voice("Maya", listOf(1f, 0f)))
        voices.save(Voice("Hen", listOf(0f, 1f)))
        // A print that says how many lines it was made of, so a merge is seen to make it again.
        return LectureReview(store, voices, player, backgroundScope, printOf = { l, id -> listOf(l.segments.count { it.speakerId == id }.toFloat()) })
            .also { assertTrue(it.open(dinner.id, "")) }
    }

    @Test
    fun namingSomeoneKeepsTheirVoiceAndKnownNamesAreOffered() = runTest {
        val review = review()
        runCurrent()
        // The voices the app knows, those not in this moment yet first.
        assertEquals(listOf("Maya", "Hen"), review.state.value?.knownNames)
        review.namePerson("speaker-2", " Maya ")
        runCurrent()
        assertEquals("Maya", review.state.value?.people?.first { it.speakerId == "speaker-2" }?.name)
        assertEquals("Maya", store.load(dinner.id)?.speakers?.first { it.id == "speaker-2" }?.name)
        assertEquals(listOf(2f), voices.all().first { it.name == "Maya" }.print)
    }

    @Test
    fun aNameSomeoneHereAlreadyHasMergesTheTwo() = runTest {
        val review = review()
        review.namePerson("speaker-2", "Maya")
        review.namePerson("speaker-4", "maya")
        runCurrent()
        val saved = store.load(dinner.id)!!
        assertEquals(listOf("speaker-1", "speaker-2", "speaker-3", "speaker-5"), saved.speakers.map { it.id })
        assertEquals("speaker-2", saved.segments.first { it.id == "s4" }.speakerId)
        assertEquals(1, saved.speakers.count { it.name == "Maya" })
        // Maya's print is made again, from her three lines now.
        assertEquals(listOf(3f), voices.all().first { it.name == "Maya" }.print)
        assertEquals(3, review.state.value?.people?.size)
    }

    @Test
    fun mergingIntoAnUnnamedSpeakerSavesNoVoice() = runTest {
        val review = review()
        review.mergeSpeakers("speaker-4", "speaker-3")
        runCurrent()
        assertEquals(listOf("Maya", "Hen"), voices.all().map { it.name })
        assertEquals(19_000L, review.state.value?.people?.first { it.speakerId == "speaker-3" }?.talkMillis)
    }

    @Test
    fun aSampleStartsAtTheirLongestLineAndASecondTapPausesIt() = runTest {
        val review = review()
        review.playPerson("speaker-2")
        assertEquals(49_000L, player.positionMillis.value)
        assertTrue(player.playing.value)
        review.playPerson("speaker-2")
        assertFalse(player.playing.value)
        review.playPerson("speaker-3")
        assertEquals(30_000L, player.positionMillis.value)
        assertTrue(player.playing.value)
    }

    @Test
    fun playingOnlyOneSpeakerSkipsTheOthersAndStopsAfterTheirLast() = runTest {
        val review = review()
        runCurrent()
        // Maya-to-be (speaker-2) speaks at 0:20-0:30 and 0:49-1:15.
        review.playOnly("speaker-2")
        runCurrent()
        assertEquals("speaker-2", review.state.value?.onlySpeaker)
        assertEquals(20_000L, player.positionMillis.value)
        assertTrue(player.playing.value)
        // Inside their line it stays; past its end it jumps to their next.
        player.positionMillis.value = 25_000; runCurrent()
        assertEquals(25_000L, player.positionMillis.value)
        player.positionMillis.value = 30_050; runCurrent()
        assertEquals(49_000L, player.positionMillis.value)
        // After their last line, it pauses.
        player.positionMillis.value = 75_010; runCurrent()
        assertFalse(player.playing.value)
        // The same speaker again: everyone, and nothing is skipped.
        review.playOnly("speaker-2")
        player.play(); player.positionMillis.value = 30_050; runCurrent()
        assertEquals(null, review.state.value?.onlySpeaker)
        assertEquals(30_050L, player.positionMillis.value)
        assertEquals(49_000L, dinner.onlyOf("speaker-2", 31_000))
        assertEquals(null, dinner.onlyOf("speaker-3", 50_000))
    }

    @Test
    fun theMomentAsTextToPaste() {
        val text = com.chencohensolution.transcriptor.notebook.transcriptText(
            dinner.copy(notes = listOf(com.chencohensolution.transcriptor.notebook.Note("n1", 21_000, "the boat"), com.chencohensolution.transcriptor.notebook.Note("n2", 50_000, ""))),
        )
        assertTrue(text.startsWith("Sat dinner\n\nHen: text s1\n\nSpeaker 2: text s2\n\n[note 0:21] the boat\n\nSpeaker 3: text s3"), text)
        assertTrue("[marked 0:50]" in text, text)
        assertTrue(text.trimEnd().endsWith("text s7"), text)
    }
}
