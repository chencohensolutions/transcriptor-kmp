package com.chencohensolution.transcriptor.notebook

import com.chencohensolution.transcriptor.recording.WavWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The lectures on disk: `notebooks/<notebook id>/<lecture id>/` under [root], with the audio and `lecture.json`
 * (version 1, the format in docs/design.md), written to a temp file and then renamed.
 */
class LectureStore(private val fileSystem: FileSystem, private val root: Path) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    fun folder(notebookId: String, lectureId: String): Path = root / "notebooks" / notebookId / lectureId

    fun audioPath(lecture: Lecture, file: String = lecture.audioFile): Path = folder(lecture.notebookId, lecture.id) / file

    fun save(lecture: Lecture) {
        val folder = folder(lecture.notebookId, lecture.id)
        fileSystem.createDirectories(folder)
        val temp = folder / "lecture.json.tmp"
        fileSystem.write(temp) { writeUtf8(json.encodeToString(LectureFileV1.of(lecture))) }
        fileSystem.atomicMove(temp, folder / "lecture.json")
    }

    /** The lecture; null when there is none. */
    fun load(notebookId: String, lectureId: String): Lecture? {
        val file = folder(notebookId, lectureId) / "lecture.json"
        if (!fileSystem.exists(file)) return null
        return json.decodeFromString<LectureFileV1>(fileSystem.read(file) { readUtf8() }).toLecture(notebookId)
    }

    /**
     * Fixes the WAV's header if a crash cut the recording off, before the audio is played or read. Not while the
     * lecture is being recorded.
     */
    fun repairAudio(lecture: Lecture) {
        val audio = audioPath(lecture)
        if (lecture.audioFile == AUDIO_WAV && fileSystem.exists(audio)) WavWriter.repair(fileSystem, audio)
    }

    /** Every lecture, in every notebook. */
    fun all(): List<Lecture> {
        val notebooks = root / "notebooks"
        if (!fileSystem.exists(notebooks)) return emptyList()
        return fileSystem.list(notebooks).flatMap { notebook ->
            fileSystem.list(notebook).mapNotNull { load(notebook.name, it.name) }
        }
    }
}

@Serializable
private data class LectureFileV1(
    val v: Int = 1,
    val id: String,
    val title: String,
    val language: String,
    val startedAt: String,
    val durationMs: Long,
    val audio: String,
    val pendingFlac: Boolean = false,
    val speakers: List<SpeakerV1> = emptyList(),
    val segments: List<SegmentV1> = emptyList(),
    val notes: List<NoteV1> = emptyList(),
) {
    @OptIn(ExperimentalTime::class)
    fun toLecture(notebookId: String) = Lecture(
        id = id, notebookId = notebookId, title = title, language = language,
        startedAtMillis = Instant.parse(startedAt).toEpochMilliseconds(), durationMillis = durationMs,
        segments = segments.map { Segment(it.id, it.startMs, it.endMs, it.text, it.model, it.edited, it.speaker) },
        notes = notes.map { Note(it.id, it.atMs, it.text) },
        speakers = speakers.map { Speaker(it.id, it.name) },
        audioFile = audio, pendingFlac = pendingFlac,
    )

    companion object {
        @OptIn(ExperimentalTime::class)
        fun of(lecture: Lecture) = LectureFileV1(
            id = lecture.id, title = lecture.title, language = lecture.language,
            startedAt = Instant.fromEpochMilliseconds(lecture.startedAtMillis).toString(),
            durationMs = lecture.durationMillis, audio = lecture.audioFile, pendingFlac = lecture.pendingFlac,
            speakers = lecture.speakers.map { SpeakerV1(it.id, it.name) },
            segments = lecture.segments.map { SegmentV1(it.id, it.startMillis, it.endMillis, it.text, it.modelId, it.edited, it.speakerId) },
            notes = lecture.notes.map { NoteV1(it.id, it.atMillis, it.text) },
        )
    }
}

@Serializable
private data class SpeakerV1(val id: String, val name: String)

@Serializable
private data class SegmentV1(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val model: String,
    val edited: Boolean = false,
    val speaker: String? = null,
)

@Serializable
private data class NoteV1(val id: String, val atMs: Long, val text: String)
