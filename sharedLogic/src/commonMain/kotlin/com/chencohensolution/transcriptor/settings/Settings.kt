package com.chencohensolution.transcriptor.settings

import com.chencohensolution.transcriptor.models.ModelChoices
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/** How a lecture's audio is kept. WAV while recording is the safest (no encoder to fail) and the largest. */
@Serializable
enum class AudioFormat {
    /** WAV, and it stays WAV: about 690 MB an hour. */
    @SerialName("wav") Wav,

    /** WAV while recording, compressed to FLAC after Stop (then the WAV is deleted): about 350–450 MB an hour. */
    @SerialName("wav-then-flac") WavThenFlac,

    /** FLAC as it records. */
    @SerialName("flac-live") FlacLive,
}

@Serializable
private data class SettingsFileV1(
    val v: Int = 1,
    val audioFormat: AudioFormat = AudioFormat.Wav,
    /** The live model chosen per language (BCP-47 tag to model id). */
    val liveModels: Map<String, String> = emptyMap(),
    /** The refine model chosen per language; a language without one refines with its first ready large model. */
    val refineModels: Map<String, String> = emptyMap(),
    /** Languages shown in Settings › Models beside the notebooks' (added there). */
    val modelLanguages: List<String> = emptyList(),
    /**
     * The one switch before Nov 2026, "Improve after the lecture": the speaker pass when it stops (and the refine pass
     * offered). Read only, into [findSpeakersOnStop], for files written before it.
     */
    val afterLecture: Boolean = true,
    /**
     * Before Nov 6, 2026: the speaker pass ran when a lecture stopped, on unless turned off. Read by nothing now: speakers
     * are found live ([speakerPassOnStop]).
     */
    val findSpeakersOnStop: Boolean? = null,
    /** The speaker pass also runs after Stop, to regroup what live found; off by default (owner's choice, Nov 6, 2026). */
    val speakerPassOnStop: Boolean = false,
    /** A lecture is improved on its own after it stops (while charging). */
    val autoImprove: Boolean = false,
    /** Each improve model's speed on this phone: milliseconds of work per millisecond of audio, from its last run. */
    val improveSpeeds: Map<String, Double> = emptyMap(),
    /** Lectures to improve on their own once the phone is charging ("notebookId/lectureId"). */
    val improvePending: List<String> = emptyList(),
    /** The lectures folder outside the app (Android: a document URI under the picked tree); null before one is picked. */
    val lectureFolder: String? = null,
    /** The user was asked to pick the lectures folder (and said not now). */
    val lectureFolderAsked: Boolean = false,
    /** The voice-print model picked for speaker labels (its id); null: the catalog's default (CAM++). */
    val voiceModel: String? = null,
    /** The user's "fewer ↔ more speakers" per voice-print model (its id), -1..1; a model without one: 0, its tuning. */
    val speakerTuning: Map<String, Float> = emptyMap(),
    /** The segmentation model picked for live speaker turns (its id); null: pyannote's. */
    val segmentationModel: String? = null,
    /** FLAC's compression level, 0 (fastest) to 8 (smallest); Android's encoder, 5 by default. */
    val flacLevel: Int = 5,
)

/** The app's settings, in `settings.json` under [root]. */
class SettingsStore(private val fileSystem: FileSystem, private val root: Path) {
    private val file get() = root / "settings.json"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    var audioFormat: AudioFormat
        get() = read().audioFormat
        set(value) = write(read().copy(audioFormat = value))

    /**
     * How hard FLAC compresses, 0 (fastest) to 8 (smallest files); the difference in size is a few percent. Used for
     * the next recording (FLAC live) and the next compression after one (WAV, then FLAC).
     */
    var flacLevel: Int
        get() = read().flacLevel.coerceIn(0, 8)
        set(value) = write(read().copy(flacLevel = value.coerceIn(0, 8)))

    /**
     * The speaker pass also runs when a lecture stops (speakers are found live while it records). Off by default: it can
     * still be run from the lecture's screen.
     */
    var findSpeakersOnStop: Boolean
        get() = read().speakerPassOnStop
        set(value) = write(read().copy(speakerPassOnStop = value))

    /**
     * A lecture is improved with its language's improve model after it stops, while the phone charges (else once it
     * does). Off: improving is by hand, from the lecture's screen, which offers it either way.
     */
    var autoImprove: Boolean
        get() = read().autoImprove
        set(value) = write(read().copy(autoImprove = value))

    /** How long [modelId] took on this phone per millisecond of audio, last time it improved a lecture; null before. */
    fun improveSpeed(modelId: String): Double? = read().improveSpeeds[modelId]

    /** Keeps how long improving [audioMillis] of audio with [modelId] took ([workMillis]). */
    fun recordImproveSpeed(modelId: String, workMillis: Long, audioMillis: Long) {
        if (audioMillis <= 0 || workMillis <= 0) return
        write(read().let { it.copy(improveSpeeds = it.improveSpeeds + (modelId to workMillis.toDouble() / audioMillis)) })
    }

    /** Lectures waiting to be improved on their own, as (notebook id, lecture id), oldest first. */
    val improvePending: List<Pair<String, String>>
        get() = read().improvePending.mapNotNull { it.split('/').takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }

    fun addImprovePending(notebookId: String, lectureId: String) =
        write(read().let { it.copy(improvePending = (it.improvePending + "$notebookId/$lectureId").distinct()) })

    fun removeImprovePending(notebookId: String, lectureId: String) =
        write(read().let { it.copy(improvePending = it.improvePending - "$notebookId/$lectureId") })

    /** Where finished lectures are kept, outside the app; null keeps them in the app's storage. */
    var lectureFolder: String?
        get() = read().lectureFolder
        set(value) = write(read().copy(lectureFolder = value))

    var lectureFolderAsked: Boolean
        get() = read().lectureFolderAsked
        set(value) = write(read().copy(lectureFolderAsked = value))

    /**
     * The voice-print model picked for speaker labels (live and the speaker pass); null: the default. Read when a
     * lecture or a speaker pass starts, so a change applies to the next one.
     */
    var voiceModel: String?
        get() = read().voiceModel
        set(value) = write(read().copy(voiceModel = value))

    /** The segmentation model picked for live speaker turns; null: the default (pyannote's). */
    var segmentationModel: String?
        get() = read().segmentationModel
        set(value) = write(read().copy(segmentationModel = value))

    /** How readily [modelId] starts a new speaker: -1 fewer speakers, 1 more, 0 its tuned thresholds. */
    fun speakerTuning(modelId: String): Float = read().speakerTuning[modelId] ?: 0f

    fun setSpeakerTuning(modelId: String, more: Float) = write(read().let {
        it.copy(speakerTuning = if (more == 0f) it.speakerTuning - modelId else it.speakerTuning + (modelId to more.coerceIn(-1f, 1f)))
    })

    /** The live model chosen for [language]; null when none was chosen (the first ready one in the catalog runs). */
    fun liveModel(language: String): String? = read().liveModels[language]

    fun setLiveModel(language: String, modelId: String) = write(read().let { it.copy(liveModels = it.liveModels + (language to modelId)) })

    /** The improve model chosen for [language]; null when none was chosen (the first ready one in [ModelChoices]). */
    fun refineModel(language: String): String? = read().refineModels[language]

    fun setRefineModel(language: String, modelId: String) = write(read().let { it.copy(refineModels = it.refineModels + (language to modelId)) })

    var modelLanguages: List<String>
        get() = read().modelLanguages
        set(value) = write(read().copy(modelLanguages = value.distinct()))

    private fun read(): SettingsFileV1 =
        if (fileSystem.exists(file)) json.decodeFromString(fileSystem.read(file) { readUtf8() }) else SettingsFileV1()

    private fun write(settings: SettingsFileV1) {
        fileSystem.createDirectories(root)
        val temp = root / "settings.json.tmp"
        fileSystem.write(temp) { writeUtf8(json.encodeToString(settings)) }
        fileSystem.atomicMove(temp, file)
    }
}
