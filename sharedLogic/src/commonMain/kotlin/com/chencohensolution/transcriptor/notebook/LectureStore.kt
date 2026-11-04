package com.chencohensolution.transcriptor.notebook

import com.chencohensolution.transcriptor.recording.WavWriter
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The lectures on disk: `notebooks/<notebook id>/<lecture id>/` under [root], with the audio, `lecture.json` (version
 * 1, the format in docs/design.md) and `transcript.md` (the lecture to read, [transcriptMarkdown]), each written to a
 * temp file and then renamed. [utcOffset] is the phone's offset from UTC at a time, for the transcript's date.
 */
class LectureStore(private val fileSystem: FileSystem, private val root: Path, private val utcOffset: (Long) -> Long = { 0 }) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    fun folder(notebookId: String, lectureId: String): Path = root / "notebooks" / notebookId / lectureId

    fun audioPath(lecture: Lecture, file: String = lecture.audioFile): Path = folder(lecture.notebookId, lecture.id) / file

    fun save(lecture: Lecture) {
        val folder = folder(lecture.notebookId, lecture.id)
        fileSystem.createDirectories(folder)
        val temp = folder / "lecture.json.tmp"
        fileSystem.write(temp) { writeUtf8(json.encodeToString(LectureFileV1.of(lecture))) }
        fileSystem.atomicMove(temp, folder / "lecture.json")
        val transcript = folder / "transcript.md.tmp"
        fileSystem.write(transcript) { writeUtf8(transcriptMarkdown(lecture, utcOffset(lecture.startedAtMillis))) }
        fileSystem.atomicMove(transcript, folder / TRANSCRIPT)
    }

    /**
     * Moves a lecture's folder (its audio, transcript and versions) to another notebook, and returns it there. Not while
     * it is being recorded or a pass runs on it.
     */
    fun move(lecture: Lecture, notebookId: String): Lecture {
        if (lecture.notebookId == notebookId) return lecture
        val to = folder(notebookId, lecture.id)
        fileSystem.createDirectories(to.parent!!)
        fileSystem.atomicMove(folder(lecture.notebookId, lecture.id), to)
        return lecture.copy(notebookId = notebookId).also(::save)
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

    /** Keeps [version] in the lecture's folder (`versions/<id>.json`); the lecture lists it in [Lecture.versions]. */
    fun saveVersion(lecture: Lecture, version: Version) {
        val dir = folder(lecture.notebookId, lecture.id) / "versions"
        fileSystem.createDirectories(dir)
        val temp = dir / "${version.info.id}.json.tmp"
        fileSystem.write(temp) { writeUtf8(json.encodeToString(VersionFileV1.of(version))) }
        fileSystem.atomicMove(temp, dir / "${version.info.id}.json")
    }

    fun loadVersion(lecture: Lecture, id: String): Version? {
        val file = folder(lecture.notebookId, lecture.id) / "versions" / "$id.json"
        if (!fileSystem.exists(file)) return null
        return json.decodeFromString<VersionFileV1>(fileSystem.read(file) { readUtf8() }).toVersion()
    }

    fun deleteVersion(lecture: Lecture, id: String) = fileSystem.delete(folder(lecture.notebookId, lecture.id) / "versions" / "$id.json")

    /**
     * Keeps the lecture's current transcript as a version and returns the lecture listing it (not saved: the caller
     * saves the lecture it makes of it).
     */
    fun keepCurrent(lecture: Lecture, now: Long): Lecture {
        if (lecture.segments.isEmpty()) return lecture
        val version = lecture.asVersion(now)
        saveVersion(lecture, version)
        return lecture.copy(versions = lecture.versions + version.info)
    }

    /** Every lecture, in every notebook (unfiled ones included). */
    fun all(): List<Lecture> {
        val notebooks = root / "notebooks"
        if (!fileSystem.exists(notebooks)) return emptyList()
        // Lecture folders only: a notebook's folder also has its voices.json.
        fun folders(dir: okio.Path) = fileSystem.list(dir).filter { fileSystem.metadataOrNull(it)?.isDirectory == true }
        return folders(notebooks).flatMap { notebook -> folders(notebook).mapNotNull { load(notebook.name, it.name) } }
    }
}

/** The readable transcript beside the audio. */
const val TRANSCRIPT = "transcript.md"

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
    val speakerPass: Boolean = false,
    val refinedWith: String? = null,
    val versions: List<VersionInfoV1> = emptyList(),
    val speakers: List<SpeakerV1> = emptyList(),
    val segments: List<SegmentV1> = emptyList(),
    val notes: List<NoteV1> = emptyList(),
) {
    @OptIn(ExperimentalTime::class)
    fun toLecture(notebookId: String) = Lecture(
        id = id, notebookId = notebookId, title = title, language = language,
        startedAtMillis = Instant.parse(startedAt).toEpochMilliseconds(), durationMillis = durationMs,
        segments = segments.map { Segment(it.id, it.startMs, it.endMs, it.text, it.model, it.edited, it.speaker, it.words.map { w -> Word(w.atMs, w.text) }) },
        notes = notes.map { Note(it.id, it.atMs, it.text) },
        speakers = speakers.map { Speaker(it.id, it.name) },
        audioFile = audio, pendingFlac = pendingFlac, speakerPass = speakerPass, refinedWith = refinedWith,
        versions = versions.map { it.toInfo() },
    )

    companion object {
        @OptIn(ExperimentalTime::class)
        fun of(lecture: Lecture) = LectureFileV1(
            id = lecture.id, title = lecture.title, language = lecture.language,
            startedAt = Instant.fromEpochMilliseconds(lecture.startedAtMillis).toString(),
            durationMs = lecture.durationMillis, audio = lecture.audioFile, pendingFlac = lecture.pendingFlac,
            speakerPass = lecture.speakerPass, refinedWith = lecture.refinedWith,
            versions = lecture.versions.map { VersionInfoV1.of(it) },
            speakers = lecture.speakers.map { SpeakerV1(it.id, it.name) },
            segments = lecture.segments.map {
                SegmentV1(it.id, it.startMillis, it.endMillis, it.text, it.modelId, it.edited, it.speakerId, it.words.map { w -> WordV1(w.atMillis, w.text) })
            },
            notes = lecture.notes.map { NoteV1(it.id, it.atMillis, it.text) },
        )
    }
}

@Serializable
private data class SpeakerV1(val id: String, val name: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class SegmentV1(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val model: String,
    val edited: Boolean = false,
    val speaker: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val words: List<WordV1> = emptyList(),
)

@Serializable
private data class WordV1(val atMs: Long, val text: String)

@Serializable
private data class NoteV1(val id: String, val atMs: Long, val text: String)

@Serializable
private data class VersionInfoV1(val id: String, val model: String, val keptAt: Long, val lines: Int, val edited: Int, val refined: Boolean = false) {
    fun toInfo() = VersionInfo(id, model, keptAt, lines, edited, refined)

    companion object {
        fun of(i: VersionInfo) = VersionInfoV1(i.id, i.modelId, i.keptAtMillis, i.lines, i.edited, i.refined)
    }
}

/** `versions/<id>.json`: an earlier transcript, in lecture.json's own line and speaker format. */
@Serializable
private data class VersionFileV1(
    val v: Int = 1,
    val info: VersionInfoV1,
    val speakerPass: Boolean = false,
    val refinedWith: String? = null,
    val speakers: List<SpeakerV1> = emptyList(),
    val segments: List<SegmentV1> = emptyList(),
) {
    fun toVersion() = Version(
        info.toInfo(),
        segments.map { Segment(it.id, it.startMs, it.endMs, it.text, it.model, it.edited, it.speaker, it.words.map { w -> Word(w.atMs, w.text) }) },
        speakers.map { Speaker(it.id, it.name) },
        speakerPass, refinedWith,
    )

    companion object {
        fun of(v: Version) = VersionFileV1(
            info = VersionInfoV1.of(v.info), speakerPass = v.speakerPass, refinedWith = v.refinedWith,
            speakers = v.speakers.map { SpeakerV1(it.id, it.name) },
            segments = v.segments.map { SegmentV1(it.id, it.startMillis, it.endMillis, it.text, it.modelId, it.edited, it.speakerId, it.words.map { w -> WordV1(w.atMillis, w.text) }) },
        )
    }
}
