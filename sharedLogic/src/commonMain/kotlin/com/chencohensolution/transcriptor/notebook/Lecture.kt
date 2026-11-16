package com.chencohensolution.transcriptor.notebook

/**
 * A label a moment can have: moments have any number of them (none included), so a label is a way to find moments
 * (a course, a person, a project), not a place they are in. [color] is an index into the app's palette, picked when it
 * was made.
 */
data class Label(
    val id: String,
    val name: String,
    val color: Int = 0,
    /** Its moments stay out of the list and search until the phone's lock opens them ("Needs fingerprint"). */
    val locked: Boolean = false,
)

/** A moment as a list shows it, with its language (it lays out its title) and its labels' ids. */
data class LectureSummary(
    val id: String,
    val title: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val noteCount: Int,
    val refined: Boolean,
    val language: String = "en",
    val labels: List<String> = emptyList(),
    /** Recorded without live transcription and not transcribed yet ([Lecture.pendingTranscript]). */
    val pendingTranscript: Boolean = false,
    /** The names of its speakers who have one, the one who talked most first: who was in it. */
    val people: List<String> = emptyList(),
    /** How many of its speakers have no name yet. */
    val unnamedPeople: Int = 0,
    /** Its first sentence of some length, to tell it from the moments around it; empty before it is transcribed. */
    val preview: String = "",
    /** Tucked away ([Lecture.tucked]): out of the list until the user shows tucked moments, never found by search. */
    val tucked: Boolean = false,
    /** Where the recording came from on the server ([Lecture.source]): `call`, `recording`, a voice message's folder; null for one recorded here. */
    val source: String? = null,
    /** Its audio is on the server only (freed from the phone, or never brought here): it plays as it streams. */
    val audioOnServer: Boolean = false,
    /** The phone is paired with a server and this moment isn't backed up there yet (only here). */
    val notOnServer: Boolean = false,
    /** Behind the phone's lock: a locked label's, or a call or voice message while those are locked ([Locks]). */
    val locked: Boolean = false,
)

/**
 * One recorded class: the audio file (WAV or FLAC, 48 kHz, stereo, 16 bit; or AAC, as Settings had it) and what was said and written in it. Every
 * time is in milliseconds from the start of the recording, so a line or a note always points into the audio.
 */
data class Lecture(
    val id: String,
    val title: String,
    /** A BCP-47 tag ("he", "en"): it picked the speech models and lays out the lines. */
    val language: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val segments: List<Segment>,
    val notes: List<Note>,
    val speakers: List<Speaker> = emptyList(),
    /** The audio's file name in the lecture's folder: [AUDIO_WAV], [AUDIO_FLAC] or [AUDIO_AAC]. */
    val audioFile: String = AUDIO_WAV,
    /** Recorded as WAV with the "WAV, then FLAC" setting, and not compressed yet. */
    val pendingFlac: Boolean = false,
    /** Recorded as WAV with "WAV, then AAC", and not encoded yet. */
    val pendingAac: Boolean = false,
    /** The speaker pass ran after the lecture: its lines follow the speakers' turns. */
    val speakerPass: Boolean = false,
    /** The model the refine pass transcribed it with again; null before it. */
    val refinedWith: String? = null,
    /** Earlier transcripts of the recording, kept when a refine pass or a switch replaced them (`versions/<id>.json`). */
    val versions: List<VersionInfo> = emptyList(),
    /** Its labels' ids ([Label]), in the order they were given. */
    val labels: List<String> = emptyList(),
    /**
     * Recorded without live transcription ("After" on the Record bar, or no live model for its language) and not
     * transcribed yet: it has its audio and notes, no lines. The transcribe pass
     * ([com.chencohensolution.transcriptor.speech.lineTimes], then the improve model) clears it.
     */
    val pendingTranscript: Boolean = false,
    /** Where the recording was paused, and when by the clock: the audio has no gap there, the clock does. */
    val pauses: List<Pause> = emptyList(),
    /** Where live transcription was switched off while recording (and on again): those stretches have no live lines. */
    val liveOff: List<LiveOff> = emptyList(),
    /**
     * Tucked away by the user (a lighter hiding than private, as in the journal app): left out of the list until the
     * user shows tucked moments with the phone's lock, never found by search. Told to the server too once backed up.
     */
    val tucked: Boolean = false,
    /**
     * The sha256 of its audio on the server, when the audio may be there only: a recording of the server's (a call, a
     * recording from before) made a moment here, or a moment whose audio Free up space removed from the phone.
     * Opening it fetches the audio again ([com.chencohensolution.transcriptor.backup.ServerBackup.bringAudio]).
     */
    val serverSha256: String? = null,
    /** Where the server says the recording came from (`call`, `recording`, `folder:whatsapp`); null for one recorded here. */
    val source: String? = null,
)

/**
 * Live transcription switched off while recording, from [fromMillis] to [toMillis] of the audio (null while it still
 * is), [offAtMillis] and [onAtMillis] by the clock (epoch ms; [onAtMillis] null when it stayed off to the end).
 */
data class LiveOff(val fromMillis: Long, val toMillis: Long?, val offAtMillis: Long, val onAtMillis: Long? = null)

/**
 * A pause in the recording at [atMillis] of the audio (where it went on after Resume), [pausedAtMillis] by the clock
 * (epoch ms) and [resumedAtMillis] when Resume was pressed; null when the moment was stopped while paused.
 */
data class Pause(val atMillis: Long, val pausedAtMillis: Long, val resumedAtMillis: Long? = null) {
    /** How long it was paused; null when it never resumed. */
    val lengthMillis get() = resumedAtMillis?.let { it - pausedAtMillis }
}

/**
 * An earlier transcript, as the lecture lists it: written by [modelId] (the live model, or a refine pass's), kept at
 * [keptAtMillis], with [lines] lines of which [edited] the user corrected. Its lines and speakers are in its own file.
 */
data class VersionInfo(
    val id: String,
    val modelId: String,
    val keptAtMillis: Long,
    val lines: Int,
    val edited: Int,
    /** Written by a refine pass (else by live transcription). */
    val refined: Boolean = false,
)

/** An earlier transcript in full: its lines and speakers. */
data class Version(
    val info: VersionInfo,
    val segments: List<Segment>,
    val speakers: List<Speaker>,
    val speakerPass: Boolean,
    /** The refine pass's model, if it wrote this version. */
    val refinedWith: String?,
)

/** The model that wrote most of the lecture's lines now. */
val Lecture.modelOfText: String? get() = segments.groupingBy { it.modelId }.eachCount().maxByOrNull { it.value }?.key

/** The current transcript, kept as a version at [now]. */
fun Lecture.asVersion(now: Long) = Version(
    VersionInfo("v$now", refinedWith ?: modelOfText.orEmpty(), now, segments.size, segments.count { it.edited }, refinedWith != null),
    segments, speakers, speakerPass, refinedWith,
)

/** Its WAV waits to be encoded (to FLAC or AAC) by [com.chencohensolution.transcriptor.recording.AudioCompression]. */
val Lecture.pendingEncode get() = pendingFlac || pendingAac

const val AUDIO_WAV = "audio.wav"
const val AUDIO_FLAC = "audio.flac"

/** AAC in ADTS frames: each frame carries its own header, so a recording cut off by a crash still plays up to its end. */
const val AUDIO_AAC = "audio.aac"

/** A line or paragraph of speech, from [startMillis] to [endMillis]. */
data class Segment(
    val id: String,
    val startMillis: Long,
    val endMillis: Long,
    val text: String,
    /** The model that wrote [text] (a [com.chencohensolution.transcriptor.models.SpeechModel] id). */
    val modelId: String,
    /** The user changed [text] by hand; a refine pass leaves it alone. */
    val edited: Boolean = false,
    /** Who said it: live speaker labels, then the speaker pass; null when unknown. */
    val speakerId: String? = null,
    /** Its words with when each was said, when the model gives them (the streaming one): to split it by speaker. */
    val words: List<Word> = emptyList(),
)

/** A word of a line, said at [atMillis]. */
data class Word(val atMillis: Long, val text: String)

/** A note typed during or after the lecture, pinned to [atMillis] (when the user started typing it). */
data class Note(
    val id: String,
    val atMillis: Long,
    val text: String,
) {
    /** A mark: a place flagged with one tap while recording, with no words (yet; editing it gives it some). */
    val isMark: Boolean get() = text.isEmpty()
}

/** A voice the refine pass told apart ("Speaker 1"), renamed by the user ("Lecturer"). */
data class Speaker(
    val id: String,
    val name: String,
)
