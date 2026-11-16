package com.chencohensolution.transcriptor.notebook

import kotlinx.serialization.Serializable

/**
 * One moment as [LectureStore]'s index keeps it: what the list shows and what the backup asks, so neither reads every
 * moment's folder (hundreds of `lecture.json` files in the lectures folder, slow through Android's document access).
 * The folders stay the truth: the index is made again from them on each start ([LectureStore.rescan]).
 */
@Serializable
data class IndexedMoment(
    val id: String,
    val title: String,
    val language: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val noteCount: Int = 0,
    val refined: Boolean = false,
    val labels: List<String> = emptyList(),
    val pendingTranscript: Boolean = false,
    val people: List<String> = emptyList(),
    val unnamedPeople: Int = 0,
    val preview: String = "",
    val tucked: Boolean = false,
    val source: String? = null,
    val serverSha256: String? = null,
    val audioFile: String = AUDIO_WAV,
    val pendingFlac: Boolean = false,
    val pendingAac: Boolean = false,
    /** Its audio is on the phone, not only on the server. */
    val hasAudio: Boolean = false,
) {
    val pendingEncode: Boolean get() = pendingFlac || pendingAac

    fun summary() = LectureSummary(
        id, title, startedAtMillis, durationMillis, noteCount, refined, language, labels, pendingTranscript,
        people = people, unnamedPeople = unnamedPeople, preview = preview, tucked = tucked, source = source,
    )
}

/** The moment as the index keeps it, with whether its audio is on the phone. */
fun Lecture.indexed(hasAudio: Boolean): IndexedMoment = summary().let { s ->
    IndexedMoment(
        id, title, language, startedAtMillis, durationMillis, s.noteCount, s.refined, labels, pendingTranscript, s.people,
        s.unnamedPeople, s.preview, tucked, source, serverSha256, audioFile, pendingFlac, pendingAac, hasAudio,
    )
}

/** `moments-index.json` in the app's own storage (version 1). */
@Serializable
internal data class MomentIndexFileV1(val v: Int = 1, val moments: List<IndexedMoment> = emptyList())
