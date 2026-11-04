package com.chencohensolution.transcriptor.notebook

/**
 * A course: its lectures share a language, which picks the speech models (Settings › Models).
 * [language] is a BCP-47 tag ("he", "en").
 */
data class Notebook(
    val id: String,
    val title: String,
    val language: String,
    val lectures: List<LectureSummary>,
)

/** A lecture as the notebook lists it. */
data class LectureSummary(
    val id: String,
    val title: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val noteCount: Int,
    val refined: Boolean,
)

/**
 * One recorded class: the audio file (WAV or FLAC, 48 kHz, stereo, 16 bit) and what was said and written in it. Every
 * time is in milliseconds from the start of the recording, so a line or a note always points into the audio.
 */
data class Lecture(
    val id: String,
    val notebookId: String,
    val title: String,
    val language: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val segments: List<Segment>,
    val notes: List<Note>,
    val speakers: List<Speaker> = emptyList(),
    /** The audio's file name in the lecture's folder: [AUDIO_WAV] or [AUDIO_FLAC]. */
    val audioFile: String = AUDIO_WAV,
    /** Recorded as WAV with the "WAV, then FLAC" setting, and not compressed yet. */
    val pendingFlac: Boolean = false,
    /** The speaker pass ran after the lecture: its lines follow the speakers' turns. */
    val speakerPass: Boolean = false,
    /** The model the refine pass transcribed it with again; null before it. */
    val refinedWith: String? = null,
    /** Earlier transcripts of the recording, kept when a refine pass or a switch replaced them (`versions/<id>.json`). */
    val versions: List<VersionInfo> = emptyList(),
)

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

const val AUDIO_WAV = "audio.wav"
const val AUDIO_FLAC = "audio.flac"

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
)

/** A voice the refine pass told apart ("Speaker 1"), renamed by the user ("Lecturer"). */
data class Speaker(
    val id: String,
    val name: String,
)
