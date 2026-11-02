package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.lab.LabData
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.notebook.VoiceStore
import com.chencohensolution.transcriptor.review.AudioPlayer
import com.chencohensolution.transcriptor.review.LectureReview
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakePlayer : AudioPlayer {
    override val positionMillis = MutableStateFlow(0L)
    override val playing = MutableStateFlow(false)
    var loaded: Path? = null
    var rate = 1f
    var released = false
    override fun load(file: Path) { loaded = file }
    override fun play() { playing.value = true }
    override fun pause() { playing.value = false }
    override fun seekTo(millis: Long) { positionMillis.value = millis }
    override fun setSpeed(speed: Float) { rate = speed }
    override fun release() { released = true }
}

class ReviewTest {
    private val fs = FakeFileSystem()
    private val root = "/data".toPath()
    private val store = LectureStore(fs, root)
    private val voices = VoiceStore(fs, root)
    private val player = FakePlayer()
    private val lecture = LabData.liveLectureWithSpeakers

    private fun TestScope.review(): LectureReview {
        store.save(lecture)
        return LectureReview(store, voices, player, backgroundScope) { _, id -> if (id == "speaker-2") listOf(0.1f, 1f) else null }
            .also { assertTrue(it.open(lecture.notebookId, lecture.id, "פסיכולוגיה")) }
    }

    @Test
    fun openingLoadsTheLectureAndItsAudio() = runTest {
        val review = review()
        runCurrent()
        assertEquals(lecture.segments, review.state.value?.lecture?.segments)
        assertEquals(store.audioPath(lecture), player.loaded)
        assertNull(review.state.value?.refine)
        review.close()
        runCurrent()
        assertTrue(player.released)
        assertNull(review.state.value)
        assertFalse(LectureReview(store, voices, player, backgroundScope).open("nb", "nothing", ""))
    }

    @Test
    fun aLinePlaysFromItsStartAndThePlayheadFollows() = runTest {
        val review = review()
        val line = lecture.segments[4]
        review.playLine(line)
        runCurrent()
        assertEquals(line.startMillis, review.state.value?.positionMillis)
        assertEquals(true, review.state.value?.playing)
        assertEquals(line.id, review.state.value?.currentLine?.id)
        review.playPause()
        runCurrent()
        assertEquals(false, review.state.value?.playing)
    }

    @Test
    fun theSpeedCycles() = runTest {
        val review = review()
        repeat(2) { review.cycleSpeed() }
        runCurrent()
        assertEquals(1.5f, review.state.value?.speed)
        assertEquals(1.5f, player.rate)
        repeat(2) { review.cycleSpeed() }
        runCurrent()
        assertEquals(1f, review.state.value?.speed)
    }

    @Test
    fun editsSpeakersAndNotesAreSaved() = runTest {
        val review = review()
        review.editLine("s3", "  הבעיה המרכזית: מחסן פסיבי. ")
        review.setLineSpeaker("s5", "speaker-2")
        review.addNote(80_000, "לחזור לזה")
        review.editNote("n1", "תוקן")
        review.deleteNote("n2")
        val saved = store.load(lecture.notebookId, lecture.id)!!
        val s3 = saved.segments.first { it.id == "s3" }
        assertEquals("הבעיה המרכזית: מחסן פסיבי." to true, s3.text to s3.edited)
        assertEquals("speaker-2", saved.segments.first { it.id == "s5" }.speakerId)
        assertEquals(listOf("n1" to "תוקן", "n4" to "לחזור לזה", "n3" to lecture.notes[2].text), saved.notes.map { it.id to it.text })
        // The same text again isn't an edit.
        review.editLine("s4", lecture.segments[3].text)
        assertFalse(store.load(lecture.notebookId, lecture.id)!!.segments.first { it.id == "s4" }.edited)
    }

    @Test
    fun aRenamedSpeakerIsRememberedByTheNotebook() = runTest {
        val review = review()
        review.renameSpeaker("speaker-2", "יעל")
        assertEquals("יעל", store.load(lecture.notebookId, lecture.id)!!.speakers.first { it.id == "speaker-2" }.name)
        assertEquals(listOf("יעל"), voices.load(lecture.notebookId).map { it.name })
        // Renamed again: the notebook's voice follows.
        review.renameSpeaker("speaker-2", "יעל כהן")
        assertEquals(listOf("יעל כהן"), voices.load(lecture.notebookId).map { it.name })
    }

    @Test
    fun reloadPicksUpTheSpeakerPass() = runTest {
        val review = review()
        store.save(lecture.copy(speakerPass = true))
        review.reload()
        runCurrent()
        assertEquals(true, review.state.value?.lecture?.speakerPass)
    }
}
