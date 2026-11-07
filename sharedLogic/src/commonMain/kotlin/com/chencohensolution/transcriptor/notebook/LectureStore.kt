package com.chencohensolution.transcriptor.notebook

import com.chencohensolution.transcriptor.recording.WavWriter
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.chencohensolution.transcriptor.speech.LiveStretch
import com.chencohensolution.transcriptor.recording.PowerReport
import com.chencohensolution.transcriptor.recording.PowerLogCsv
import okio.FileSystem
import okio.Path
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The moments on disk: `moments/<lecture id>/` with the audio, `lecture.json` (version 1, the format
 * in docs/design.md) and `transcript.md` (the lecture to read, [transcriptMarkdown]), each written to a temp file and
 * then renamed. A lecture is in one of two places: the app's own storage under [root] (while it is recorded, until
 * [archive] moves it, or always when there is no library), or the [library], the lectures folder outside the app.
 * [utcOffset] is the phone's offset from UTC at a time, for the transcript's date.
 */
class LectureStore(fileSystem: FileSystem, root: Path, private val utcOffset: (Long) -> Long = { 0 }) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    /** The app's own storage. */
    val local = Place(fileSystem, root)

    /** The lectures folder outside the app, once the user picked one; finished lectures move there ([archive]). */
    var library: Place? = null

    /** Where a lecture is: here, or in the library; a new one is here. */
    private fun placeOf(lectureId: String): Place {
        if (local.exists(local.folder(lectureId))) return local
        val library = library ?: return local
        return if (library.exists(library.folder(lectureId))) library else local
    }

    fun folder(lectureId: String): Path = placeOf(lectureId).folder(lectureId)

    fun audioPath(lecture: Lecture, file: String = lecture.audioFile): Path = folder(lecture.id) / file

    /** The lecture is in the app's own storage, not (yet) in the library. */
    fun isLocal(lecture: Lecture) = placeOf(lecture.id) == local

    fun save(lecture: Lecture) {
        val place = placeOf(lecture.id)
        val fs = place.fileSystem
        val folder = place.folder(lecture.id)
        fs.createDirectories(folder)
        val temp = folder / "lecture.json.tmp"
        fs.write(temp) { writeUtf8(json.encodeToString(LectureFileV1.of(lecture))) }
        fs.atomicMove(temp, folder / "lecture.json")
        val transcript = folder / "transcript.md.tmp"
        fs.write(transcript) { writeUtf8(transcriptMarkdown(lecture, utcOffset(lecture.startedAtMillis))) }
        fs.atomicMove(transcript, folder / TRANSCRIPT)
    }

    /** Gives the moment these labels (ids), and returns it. Not while it is being recorded or a pass runs on it. */
    fun setLabels(lecture: Lecture, labels: List<String>): Lecture =
        if (lecture.labels == labels) lecture else lecture.copy(labels = labels).also(::save)

    /**
     * Moves a finished lecture from the app's storage to the library: copied, checked file by file, then deleted here.
     * False when there is no library or it isn't here; a failed copy leaves it here (and throws). Not while it is being
     * recorded, a pass runs on it, or it is open.
     */
    fun archive(lecture: Lecture): Boolean {
        val library = library ?: return false
        val from = local.folder(lecture.id)
        if (!local.exists(from)) return false
        val to = library.folder(lecture.id)
        if (library.exists(to)) library.fileSystem.deleteRecursively(to)
        copyChecked(local, from, library, to)
        checkNotNull(read(library, lecture.id)) { "${lecture.id}'s lecture.json doesn't read back" }
        local.fileSystem.deleteRecursively(from)
        return true
    }

    /**
     * Deletes a lecture's folder for good, wherever it is (here or in the library): its audio, `lecture.json`, the
     * transcript and its versions. False when there was none. Not while it is being recorded or a pass runs on it.
     */
    fun delete(lectureId: String): Boolean {
        val place = placeOf(lectureId)
        val folder = place.folder(lectureId)
        if (!place.exists(folder)) return false
        place.fileSystem.deleteRecursively(folder)
        return true
    }

    /** The lecture as a Word file ([transcriptDocx]), with its labels ([labelNames]). */
    fun word(lecture: Lecture, labelNames: String?): ByteArray = transcriptDocx(lecture, labelNames, utcOffset(lecture.startedAtMillis))

    /** Saves the lecture's Word file in its folder, beside its recording (replacing an earlier one); returns its name. */
    fun saveWord(lecture: Lecture, labelNames: String?): String {
        val place = placeOf(lecture.id)
        val folder = place.folder(lecture.id)
        val name = docxName(lecture)
        val temp = folder / "$name.tmp"
        place.fileSystem.write(temp) { write(word(lecture, labelNames)) }
        place.fileSystem.atomicMove(temp, folder / name)
        return name
    }

    /** The lecture; null when there is none. */
    fun load(lectureId: String): Lecture? = read(placeOf(lectureId), lectureId)

    private fun read(place: Place, lectureId: String): Lecture? {
        val file = place.folder(lectureId) / "lecture.json"
        if (!place.exists(file)) return null
        return json.decodeFromString<LectureFileV1>(place.fileSystem.read(file) { readUtf8() }).toLecture()
    }

    /**
     * Fixes the WAV's header if a crash cut the recording off, before the audio is played or read. Not while the
     * lecture is being recorded. Only here: a lecture reaches the library finished.
     */
    fun repairAudio(lecture: Lecture) {
        if (!isLocal(lecture)) return
        val audio = audioPath(lecture)
        if (lecture.audioFile == AUDIO_WAV && local.fileSystem.exists(audio)) WavWriter.repair(local.fileSystem, audio)
    }

    /** Keeps [version] in the lecture's folder (`versions/<id>.json`); the lecture lists it in [Lecture.versions]. */
    fun saveVersion(lecture: Lecture, version: Version) {
        val place = placeOf(lecture.id)
        val dir = place.folder(lecture.id) / "versions"
        place.fileSystem.createDirectories(dir)
        val temp = dir / "${version.info.id}.json.tmp"
        place.fileSystem.write(temp) { writeUtf8(json.encodeToString(VersionFileV1.of(version))) }
        place.fileSystem.atomicMove(temp, dir / "${version.info.id}.json")
    }

    fun loadVersion(lecture: Lecture, id: String): Version? {
        val place = placeOf(lecture.id)
        val file = place.folder(lecture.id) / "versions" / "$id.json"
        if (!place.exists(file)) return null
        return json.decodeFromString<VersionFileV1>(place.fileSystem.read(file) { readUtf8() }).toVersion()
    }

    /**
     * Keeps what live speaker turns decided while [lecture] was recorded (`speakers-live.json`: the voice-print [model]'s
     * id, the segmentation model's ([turns], null for pyannote's) and the stretches with their prints), so its speakers
     * can be regrouped or the thresholds tuned on it later.
     */
    fun saveLiveSpeakers(lecture: Lecture, model: String, stretches: List<LiveStretch>, turns: String? = null) {
        val place = placeOf(lecture.id)
        val folder = place.folder(lecture.id)
        place.fileSystem.createDirectories(folder)
        val temp = folder / "$LIVE_SPEAKERS.tmp"
        val file = LiveSpeakersFileV1(model = model, turns = turns, stretches = stretches.map { LiveStretchV1(it.startMillis, it.endMillis, it.speaker, it.print, it.follows) })
        place.fileSystem.write(temp) { writeUtf8(compact.encodeToString(file)) }
        place.fileSystem.atomicMove(temp, folder / LIVE_SPEAKERS)
    }

    /** What [saveLiveSpeakers] kept; null for a lecture recorded without live speaker turns. */
    fun loadLiveSpeakers(lecture: Lecture): KeptLiveSpeakers? {
        val place = placeOf(lecture.id)
        val file = place.folder(lecture.id) / LIVE_SPEAKERS
        if (!place.exists(file)) return null
        val f = compact.decodeFromString<LiveSpeakersFileV1>(place.fileSystem.read(file) { readUtf8() })
        return KeptLiveSpeakers(f.model, f.stretches.map { LiveStretch(it.startMs, it.endMs, it.speaker, it.print, it.follows) }, f.turns)
    }

    private val compact = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    /** Keeps how much power recording [lecture] took (`power.json`, version 1), beside its audio. */
    fun savePower(lecture: Lecture, report: PowerReport) {
        val place = placeOf(lecture.id)
        val folder = place.folder(lecture.id)
        place.fileSystem.createDirectories(folder)
        val temp = folder / "$POWER.tmp"
        place.fileSystem.write(temp) { writeUtf8(json.encodeToString(report)) }
        place.fileSystem.atomicMove(temp, folder / POWER)
    }

    /** [savePower]'s report; null for a lecture recorded before it existed. */
    fun loadPower(lecture: Lecture): PowerReport? {
        val place = placeOf(lecture.id)
        val file = place.folder(lecture.id) / POWER
        if (!place.exists(file)) return null
        return runCatching { json.decodeFromString<PowerReport>(place.fileSystem.read(file) { readUtf8() }) }.getOrNull()
    }

    /** Adds [row] to `power-log.csv` in the app's own storage (its header first, once). */
    fun appendPowerLog(row: String) {
        val file = local.root / POWER_LOG
        val fs = local.fileSystem
        val before = if (fs.exists(file)) fs.read(file) { readUtf8() } else PowerLogCsv.HEADER + "\n"
        val temp = local.root / "$POWER_LOG.tmp"
        fs.createDirectories(local.root)
        fs.write(temp) { writeUtf8(before + row + "\n") }
        fs.atomicMove(temp, file)
    }

    fun deleteVersion(lecture: Lecture, id: String) {
        val place = placeOf(lecture.id)
        place.fileSystem.delete(place.folder(lecture.id) / "versions" / "$id.json")
    }

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

    /** Every moment, here and in the library. */
    fun all(): List<Lecture> = listOfNotNull(local, library).flatMap { place ->
        place.folders(place.moments).mapNotNull { runCatching { read(place, it.name) }.getOrNull() }
    }.distinctBy { it.id }

    /** The lectures still in the app's own storage. */
    fun localLectures(): List<Lecture> = local.folders(local.moments).mapNotNull { runCatching { read(local, it.name) }.getOrNull() }
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
    /** Its labels' ids (`labels.json`). */
    val labels: List<String> = emptyList(),
) {
    @OptIn(ExperimentalTime::class)
    fun toLecture() = Lecture(
        id = id, title = title, language = language, labels = labels,
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
            labels = lecture.labels,
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

/** A lecture's live speaker turns ([LectureStore.saveLiveSpeakers]): voice-print [model], segmentation model [turns] (null: pyannote's). */
data class KeptLiveSpeakers(val model: String, val stretches: List<LiveStretch>, val turns: String? = null)

/** `speakers-live.json`: live speaker turns' stretches of a lecture, by voice-print [model] on [turns]' segmentation. */
@Serializable
private data class LiveSpeakersFileV1(val v: Int = 1, val model: String, val turns: String? = null, val stretches: List<LiveStretchV1> = emptyList())

@Serializable
private data class LiveStretchV1(
    val startMs: Long,
    val endMs: Long,
    val speaker: String,
    val print: List<Float>? = null,
    val follows: Int? = null,
)

const val LIVE_SPEAKERS = "speakers-live.json"
const val POWER = "power.json"
const val POWER_LOG = "power-log.csv"
