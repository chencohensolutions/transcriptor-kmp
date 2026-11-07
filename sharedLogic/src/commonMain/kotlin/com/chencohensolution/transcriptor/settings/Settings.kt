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

/** How the lecture screen shows the processors' load while recording (Settings › Advanced). */
enum class ProcessorMeterStyle(val key: String) {
    /** No meters, and nothing is read. */
    Off("off"),

    /** A slim strip of three bars under the top bar: CPU (this app), GPU (the phone), NPU (our use). */
    Bars("bars"),

    /** A small chip with three tiny bars; tapping it opens a sheet with the bars, the clocks and the NPU's temperature. */
    Chip("chip"),

    /** The last minute as three small graphs under the top bar; tapping folds them into one line. */
    Graphs("graphs");

    companion object {
        /** The style kept as [key]; anything else (a newer app's) is [Off]. */
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Off
    }
}

/**
 * FLAC's compression levels: 0 is the fastest, 8 the smallest file. Every level keeps the same sound (FLAC is lossless);
 * from 0 to 8 the file shrinks by a few percent and encoding takes several times longer.
 */
val FLAC_LEVELS = 0..8

/** The level used before it was a setting, and the reference encoder's default. */
const val FLAC_DEFAULT_LEVEL = 5

@Serializable
private data class SettingsFileV1(
    val v: Int = 1,
    val audioFormat: AudioFormat = AudioFormat.Wav,
    /** The FLAC compression level ([FLAC_LEVELS]), for both FLAC formats. */
    val flacLevel: Int = FLAC_DEFAULT_LEVEL,
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
    /** Moments to improve on their own once the phone is charging (lecture ids; "notebookId/lectureId" before labels). */
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
    /** [ProcessorMeterStyle.key]; kept as text so a style this version doesn't know reads as off. */
    val processorMeters: String = ProcessorMeterStyle.Off.key,
)

/** The app's settings, in `settings.json` under [root]. */
class SettingsStore(private val fileSystem: FileSystem, private val root: Path) {
    private val file get() = root / "settings.json"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    var audioFormat: AudioFormat
        get() = read().audioFormat
        set(value) = write(read().copy(audioFormat = value))

    /** The FLAC compression level for "WAV, then FLAC" and "FLAC live": 0 the fastest, 8 the smallest ([FLAC_LEVELS]). */
    var flacLevel: Int
        get() = read().flacLevel.coerceIn(FLAC_LEVELS)
        set(value) = write(read().copy(flacLevel = value.coerceIn(FLAC_LEVELS)))

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

    /**
     * Moments waiting to be improved on their own, by id, oldest first (kept before labels as "notebook id/lecture
     * id": read as the lecture id).
     */
    val improvePending: List<String>
        get() = read().improvePending.map { it.substringAfterLast('/') }.distinct()

    fun addImprovePending(lectureId: String) =
        write(read().let { it.copy(improvePending = (it.improvePending.map { p -> p.substringAfterLast('/') } + lectureId).distinct()) })

    fun removeImprovePending(lectureId: String) =
        write(read().let { it.copy(improvePending = it.improvePending.filter { p -> p.substringAfterLast('/') != lectureId }) })

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

    /** The processor meters on the lecture screen while recording; off by default. */
    var processorMeters: ProcessorMeterStyle
        get() = ProcessorMeterStyle.of(read().processorMeters)
        set(value) = write(read().copy(processorMeters = value.key))

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
