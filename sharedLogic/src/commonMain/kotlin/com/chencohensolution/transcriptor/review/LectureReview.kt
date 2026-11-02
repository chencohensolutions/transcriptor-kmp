package com.chencohensolution.transcriptor.review

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.VoiceStore
import com.chencohensolution.transcriptor.speech.Voice
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * A recorded lecture, opened: playing it ([AudioPlayer]), correcting its lines and their speakers, naming the speakers
 * (in the notebook's [voices] too, with a voice print from [printOf]), and notes after the lecture. Every change is
 * saved to `lecture.json` at once. [speakerPass] is fed by the app, which runs the pass in the background.
 */
class LectureReview(
    private val store: LectureStore,
    private val voices: VoiceStore?,
    private val player: AudioPlayer,
    scope: CoroutineScope,
    /** The average voice print of a speaker's lines in a lecture; null when it can't be made (no model). */
    private val printOf: (Lecture, speakerId: String) -> List<Float>? = { _, _ -> null },
) {
    private class Open(val notebookTitle: String, val lecture: Lecture, val speed: Float)

    private val open = MutableStateFlow<Open?>(null)

    /** The speaker pass for the open lecture: null when it can't run here. */
    val speakerPass = MutableStateFlow<PassState?>(null)

    val state: StateFlow<ReviewState?> =
        combine(open, player.positionMillis, player.playing, speakerPass) { o, position, playing, speakers ->
            o?.let {
                ReviewState(
                    notebookTitle = it.notebookTitle, lecture = it.lecture, positionMillis = position, playing = playing,
                    speed = it.speed, refineModelName = null, refine = null, speakerPass = speakers,
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    val lecture get() = open.value?.lecture

    /** Opens a lecture; false when it isn't there. */
    fun open(notebookId: String, lectureId: String, notebookTitle: String): Boolean {
        close()
        val lecture = store.load(notebookId, lectureId) ?: return false
        store.repairAudio(lecture)
        player.load(store.audioPath(lecture))
        open.value = Open(notebookTitle, lecture, 1f)
        return true
    }

    fun close() {
        if (open.value == null) return
        player.pause()
        player.release()
        open.value = null
    }

    /** Reads the open lecture again from disk (after the speaker pass changed it), keeping the playback. */
    fun reload() {
        val o = open.value ?: return
        val lecture = store.load(o.lecture.notebookId, o.lecture.id) ?: return
        open.value = Open(o.notebookTitle, lecture, o.speed)
    }

    fun playPause() {
        val o = open.value ?: return
        if (player.playing.value) {
            player.pause()
        } else {
            if (player.positionMillis.value >= o.lecture.durationMillis - 200) player.seekTo(0)
            player.play()
        }
    }

    fun seek(atMillis: Long) {
        val o = open.value ?: return
        player.seekTo(atMillis.coerceIn(0, o.lecture.durationMillis))
    }

    /** Plays from a line's start. */
    fun playLine(line: Segment) {
        player.seekTo(line.startMillis)
        player.play()
    }

    fun cycleSpeed() {
        val o = open.value ?: return
        val speed = SPEEDS[(SPEEDS.indexOf(o.speed) + 1) % SPEEDS.size]
        player.setSpeed(speed)
        open.value = Open(o.notebookTitle, o.lecture, speed)
    }

    /** The user's text for a line: marked edited, so the refine pass keeps it. */
    fun editLine(id: String, text: String) = change { l ->
        val clean = text.trim()
        if (clean.isEmpty()) l
        else l.copy(segments = l.segments.map { if (it.id == id && it.text != clean) it.copy(text = clean, edited = true) else it })
    }

    fun setLineSpeaker(lineId: String, speakerId: String) =
        change { l -> l.copy(segments = l.segments.map { if (it.id == lineId) it.copy(speakerId = speakerId) else it }) }

    /** Names a speaker in this lecture and, when a voice print can be made, in the notebook's voices. */
    fun renameSpeaker(id: String, name: String) {
        val clean = name.trim()
        val before = lecture ?: return
        if (clean.isEmpty()) return
        val old = before.speakers.firstOrNull { it.id == id }?.name.orEmpty()
        val after = change { l -> l.copy(speakers = l.speakers.map { if (it.id == id) it.copy(name = clean) else it }) } ?: return
        if (voices == null) return
        if (old.isNotEmpty() && old != clean) voices.rename(after.notebookId, old, clean)
        printOf(after, id)?.let { voices.save(after.notebookId, Voice(clean, it)) }
    }

    fun addNote(atMillis: Long, text: String) = change { l ->
        val clean = text.trim()
        if (clean.isEmpty()) return@change l
        val id = "n${(l.notes.maxOfOrNull { it.id.removePrefix("n").toIntOrNull() ?: 0 } ?: 0) + 1}"
        l.copy(notes = (l.notes + Note(id, atMillis, clean)).sortedBy { it.atMillis })
    }

    fun editNote(id: String, text: String) = change { l ->
        val clean = text.trim()
        if (clean.isEmpty()) l else l.copy(notes = l.notes.map { if (it.id == id) it.copy(text = clean) else it })
    }

    fun deleteNote(id: String) = change { l -> l.copy(notes = l.notes.filter { it.id != id }) }

    private val saveLock = SynchronizedObject()

    /** Applies [edit] to the open lecture and saves it; the lecture after, or null when none is open. */
    private fun change(edit: (Lecture) -> Lecture): Lecture? {
        var saved: Lecture? = null
        open.update { o ->
            o ?: return@update null
            val changed = edit(o.lecture)
            saved = changed.takeIf { it != o.lecture }
            Open(o.notebookTitle, changed, o.speed)
        }
        saved?.let { synchronized(saveLock) { store.save(it) } }
        return open.value?.lecture
    }

    companion object {
        val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f)
    }
}
