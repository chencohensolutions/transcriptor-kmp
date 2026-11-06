package com.chencohensolution.transcriptor.review

import com.chencohensolution.transcriptor.speech.LiveStretch
import com.chencohensolution.transcriptor.speech.SpeakerTuning
import com.chencohensolution.transcriptor.speech.VoicePrintThresholds
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
 * saved to `lecture.json` at once. [passes] is fed by the app, which runs the passes in the background.
 */
class LectureReview(
    private val store: LectureStore,
    private val voices: VoiceStore?,
    private val player: AudioPlayer,
    scope: CoroutineScope,
    /** The average voice print of a speaker's lines in a lecture; null when it can't be made (no model). */
    private val printOf: (Lecture, speakerId: String) -> List<Float>? = { _, _ -> null },
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    /** The user's "fewer ↔ more speakers" for a voice-print model, and setting it ([SettingsStore.speakerTuning]). */
    private val speakerTuning: (modelId: String) -> Float = { 0f },
    private val setSpeakerTuning: (modelId: String, more: Float) -> Unit = { _, _ -> },
) {
    private class Open(val notebookTitle: String, val lecture: Lecture, val speed: Float) {
        var power: com.chencohensolution.transcriptor.recording.PowerReport? = null
        var powerRead = false
        /** The live stretches kept with the lecture (model id, stretches), read once. */
        var live: com.chencohensolution.transcriptor.notebook.KeptLiveSpeakers? = null
        var liveRead = false
    }

    private fun Open.liveSpeakers(): com.chencohensolution.transcriptor.notebook.KeptLiveSpeakers? {
        if (!liveRead) {
            live = runCatching { store.loadLiveSpeakers(lecture) }.getOrNull()
            liveRead = true
        }
        // The speaker pass regroups every line: the stretches no longer say what live decided.
        return live?.takeIf { !lecture.speakerPass }
    }

    private fun Open.power(): com.chencohensolution.transcriptor.recording.PowerReport? {
        if (!powerRead) {
            power = runCatching { store.loadPower(lecture) }.getOrNull()
            powerRead = true
        }
        return power
    }

    private var correctionsFor: Lecture? = null
    private var corrections: Int? = null

    private fun Open.corrections(): Int? {
        if (correctionsFor !== lecture) {
            corrections = liveSpeakers()?.let { kept -> SpeakerTuning.corrections(lecture.segments, kept.stretches) }
            correctionsFor = lecture
        }
        return corrections
    }

    private val open = MutableStateFlow<Open?>(null)

    /**
     * The passes over the recording for the open lecture, fed by the app: the speaker pass null when it can't run
     * here; the improve pass with its models, the Settings default and the model to get when there is none.
     */
    data class Passes(
        val speakers: PassState? = null,
        val refine: PassState = PassState.NotRun,
        val refineModels: List<ModelChoice> = emptyList(),
        val improveDefault: String? = null,
        val improveToGet: ModelToGet? = null,
    )

    val passes = MutableStateFlow(Passes())

    val state: StateFlow<ReviewState?> =
        combine(open, player.positionMillis, player.playing, passes) { o, position, playing, p ->
            o?.let {
                ReviewState(
                    notebookTitle = it.notebookTitle, lecture = it.lecture, positionMillis = position, playing = playing,
                    speed = it.speed, refineModels = p.refineModels, refine = p.refine, speakerPass = p.speakers,
                    improveDefault = p.improveDefault, improveToGet = p.improveToGet, speakerCorrections = it.corrections(), power = it.power(),
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

    /** Reads the open lecture again from disk (after a pass changed it), keeping the playback. */
    fun reload() {
        val o = open.value ?: return
        val lecture = store.load(o.lecture.notebookId, o.lecture.id) ?: return
        open.value = Open(o.notebookTitle, lecture, o.speed)
    }

    /**
     * Makes an earlier transcript the current one: its lines, speakers and edits come back, and the transcript it
     * replaces is kept as a version, so switching back is always possible. Notes are the lecture's, not a version's.
     */
    fun switchTo(versionId: String) {
        val o = open.value ?: return
        val chosen = store.loadVersion(o.lecture, versionId) ?: return
        val kept = store.keepCurrent(o.lecture.copy(versions = o.lecture.versions.filter { it.id != versionId }), now())
        val switched = kept.copy(
            segments = chosen.segments, speakers = chosen.speakers, speakerPass = chosen.speakerPass, refinedWith = chosen.refinedWith,
        )
        synchronized(saveLock) { store.save(switched) }
        store.deleteVersion(o.lecture, versionId)
        open.value = Open(o.notebookTitle, switched, o.speed)
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

    /**
     * "Tune on this lecture": the voice-print model's best "fewer ↔ more speakers" by the user's labels here
     * ([SpeakerTuning]); null without live stretches or labels to judge by. Replays the stored prints: quick.
     */
    fun tuneSpeakers(): TuneProposal? {
        val o = open.value ?: return null
        val kept = o.liveSpeakers() ?: return null
        val model = kept.model
        if (model.isEmpty()) return null
        val now = speakerTuning(model)
        // Tuned against the threshold of the pair it was recorded with: the slider is an offset on it.
        val result = SpeakerTuning.tune(o.lecture.segments, kept.stretches, VoicePrintThresholds.live(model, kept.turns), now) ?: return null
        return TuneProposal(model, now, result)
    }

    /** Applies a proposal: the model's setting, from the next recording. */
    fun applySpeakerTuning(proposal: TuneProposal) = setSpeakerTuning(proposal.model, proposal.result.more)

    /** A tuning of [model]'s setting from [now] to [result]'s. */
    data class TuneProposal(val model: String, val now: Float, val result: SpeakerTuning.Result)

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
