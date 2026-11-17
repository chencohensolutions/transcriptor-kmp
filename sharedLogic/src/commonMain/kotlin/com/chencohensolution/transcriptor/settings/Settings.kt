package com.chencohensolution.transcriptor.settings

import com.chencohensolution.transcriptor.models.DefaultModels
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

    /** AAC as it records ([AacSettings]), on the phone's hardware encoder when it has one: lossy, about 30 MB an hour at 64 kbps. */
    @SerialName("aac-live") AacLive,

    /** WAV while recording, encoded to AAC ([AacSettings]) after the moment, as [EncodeAfter] says; then the WAV is deleted. */
    @SerialName("wav-then-aac") WavThenAac,
}

/** When a WAV recorded for "WAV, then FLAC" or "WAV, then AAC" is encoded. */
@Serializable
enum class EncodeAfter {
    /** As soon as the moment stops (and its passes are done). */
    @SerialName("stop") Stop,

    /** Once the phone is on the charger with at least [ENCODE_MIN_BATTERY]% battery. */
    @SerialName("charging") Charging,
}

/** The battery, in percent, "encode when charging" waits for besides the charger. */
const val ENCODE_MIN_BATTERY = 30

/** Encoding may start now: right after Stop for [EncodeAfter.Stop], else on the charger with enough battery. */
fun EncodeAfter.allows(charging: Boolean, batteryPercent: Int) =
    this == EncodeAfter.Stop || (charging && batteryPercent >= ENCODE_MIN_BATTERY)

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

/** How the recording screen draws the sound level (Settings › While recording). */
enum class LevelMeterStyle(val key: String, val needsBands: Boolean = false) {
    /** Five small bars that follow the level, as before; a set per channel when the recording is stereo. */
    Bars("bars"),

    /** A level bar per channel, with a peak that holds; red near the top, where it would clip. */
    Lines("lines"),

    /** The last 8 seconds as a scrolling strip, the left channel above the middle line and the right one below. */
    Strip("strip"),

    /** The voice range now, 80 Hz to 8 kHz in bands, with both channels' levels as thin lines. */
    Spectrum("spectrum", needsBands = true),

    /** The last 8 seconds of the voice range as a spectrogram, with both channels' levels beside it. */
    Spectrogram("spectrogram", needsBands = true);

    companion object {
        /** The style kept as [key]; anything else (a newer app's) is [Bars]. */
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Bars
    }
}

/**
 * FLAC's compression levels: 0 is the fastest, 8 the smallest file. Every level keeps the same sound (FLAC is lossless);
 * from 0 to 8 the file shrinks by a few percent and encoding takes several times longer.
 */
val FLAC_LEVELS = 0..8

/** The level used before it was a setting, and the reference encoder's default. */
const val FLAC_DEFAULT_LEVEL = 8

/**
 * "Fewer ↔ more speakers" for a voice-print model the user hasn't moved the slider of: the owner's settings on the
 * S25 (Nov 15, 2026), by model id. Any other model starts in the middle (0).
 */
val DEFAULT_SPEAKER_TUNING = mapOf(
    "wespeaker-resnet293-lm" to 0.5f,
    "3dspeaker-campplus-zh-en-advanced" to -0.2f,
    "3dspeaker-eres2netv2-zh-cn" to -0.3f,
)

@Serializable
private data class SettingsFileV1(
    val v: Int = 1,
    /** A new install records as the owner does (Nov 15, 2026): AAC as it records, [AacSettings]' defaults. */
    // The owner's settings on their S25 (read from the phone, Nov 16, 2026): WAV while recording, AAC (256 kbps
    // stereo) right after the moment, a quiet notification, no processor meters.
    val audioFormat: AudioFormat = AudioFormat.WavThenAac,
    /** The FLAC compression level ([FLAC_LEVELS]), for both FLAC formats. */
    val flacLevel: Int = FLAC_DEFAULT_LEVEL,
    /** How "AAC live" records, and what "WAV, then AAC" encodes to. */
    val aac: AacSettings = AacSettings(),
    /** FLAC's sample rate, channels and bit depth (the bit depth also for the WAV kept before FLAC, and plain WAV). */
    val flac: FlacFormat = FlacFormat(),
    /**
     * The recording profiles by [QualityProfile.key] (Nov 17, 2026); empty in a file from before: made from the fields
     * above ([audioFormat], [aac], [flac], [flacLevel], [micInput]) on the first read.
     */
    val profiles: Map<String, ProfileSettings> = emptyMap(),
    /** The profile moments record with ([QualityProfile.key]: high, normal or low); null: from before profiles. */
    val activeProfile: String? = null,
    /** Record as WAV first, encode afterwards (the battery's, for every profile); null: from [audioFormat]. */
    val wavFirst: Boolean? = null,
    /** When "WAV, then FLAC" and "WAV, then AAC" encode. */
    val encodeAfter: EncodeAfter = EncodeAfter.Stop,
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
    /** When a moment recorded with "After" is transcribed: right after Stop, or on the charger with enough battery. */
    val transcribeWhen: EncodeAfter = EncodeAfter.Charging,
    /** Moments are transcribed live while they record; off (the default): recorded only, transcribed after. */
    val recordLive: Boolean = true,
    /** The Record bar's Only record (owner's request, Nov 15, 2026): no transcript at all until the user asks; wins over [recordLive]. */
    val recordOnly: Boolean = false,
    /** The Record bar's Music (owner's request, Nov 17, 2026): FLAC, no voice processing, no transcript; wins over the two above. */
    val recordMusic: Boolean = false,
    /** While recording, the notification says nothing about it: silent, hidden on the lock screen, no buttons. */
    val quietRecording: Boolean = true,
    /** The microphone picked to record from ([MicInput]); null: the phone's own. */
    val micInput: com.chencohensolution.transcriptor.recording.MicInput? = null,
    /** Stop asks for a second tap, in the recording screens and on the Quick Settings tile; off by default (owner's choice, Nov 15, 2026). */
    val stopTwice: Boolean = false,
    /** Calls stay out of the moments list and search until the phone's lock opens them (owner's request, Nov 16, 2026). */
    val lockCalls: Boolean = false,
    /** The same for voice messages. */
    val lockVoiceMessages: Boolean = false,
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
    /** [LevelMeterStyle.key]; kept as text so a style this version doesn't know reads as the bars. */
    val levelMeter: String = LevelMeterStyle.Bars.key,
    /** First run's "Get your models" was answered (downloaded or Later); it isn't offered again. */
    val firstRunDone: Boolean = false,
    /** Advanced mode: the Record bar also shows the quality and battery presets (owner's request, Nov 17, 2026). */
    val advancedMode: Boolean = false,
)

/** The app's settings, in `settings.json` under [root]. */
class SettingsStore(private val fileSystem: FileSystem, private val root: Path) {
    private val file get() = root / "settings.json"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    /** The settings with the profiles filled in: those from before profiles become the profile they match. */
    private fun profiled(f: SettingsFileV1): SettingsFileV1 {
        if (f.profiles.isNotEmpty() && f.activeProfile != null) return f
        val legacy = ProfileSettings(Codec.of(f.audioFormat), f.aac.valid(), f.flac.valid(), f.flacLevel.coerceIn(FLAC_LEVELS), f.micInput)
        fun same(a: AacSettings, b: AacSettings) = a.profile == b.profile && a.sampleRate == b.sampleRate && a.channels == b.channels && a.bitrateKbps == b.bitrateKbps
        val matched = when {
            legacy.codec == Codec.Flac && legacy.flac == QualityProfile.HIGH_FLAC -> QualityProfile.High
            legacy.codec == Codec.Aac && same(legacy.aac, QualityProfile.LOW_AAC) -> QualityProfile.Low
            else -> QualityProfile.Normal
        }
        // The microphone picked before applies to every profile; the rest are their defaults.
        val profiles = QualityProfile.entries.associate { p ->
            p.key to if (p == matched) legacy else p.defaults.copy(micInput = f.micInput)
        }
        return f.copy(profiles = profiles + f.profiles, activeProfile = f.activeProfile ?: matched.key, wavFirst = f.wavFirst ?: f.audioFormat.wavFirst)
    }

    /** [profile]'s settings. */
    fun profile(profile: QualityProfile): ProfileSettings =
        profiled(read()).profiles[profile.key]?.valid() ?: profile.defaults

    fun setProfile(profile: QualityProfile, value: ProfileSettings) =
        write(profiled(read()).let { it.copy(profiles = it.profiles + (profile.key to value.valid())) })

    /** The settings of the profile kept as [key] (a moment's [com.chencohensolution.transcriptor.notebook.Lecture.profile]); unknown or null: the active one. */
    fun profileFor(key: String?): ProfileSettings = profile(QualityProfile.of(key) ?: activeProfile)

    /** Puts [profile] back to its defaults, the microphone kept. */
    fun resetProfile(profile: QualityProfile) = setProfile(profile, profile.defaults.copy(micInput = profile(profile).micInput))

    /** The profile moments record with: High, Normal or Low (Music is the Music mode's). */
    var activeProfile: QualityProfile
        get() = QualityProfile.of(profiled(read()).activeProfile)?.takeIf { it != QualityProfile.Music } ?: QualityProfile.Normal
        set(value) {
            // Music is the Music mode's: it is never the one moments record with.
            if (value != QualityProfile.Music) write(profiled(read()).copy(activeProfile = value.key))
        }

    /** Record as WAV first and encode afterwards, for every profile (Music excepted: it encodes as it records). */
    var wavFirst: Boolean
        get() = profiled(read()).wavFirst == true
        set(value) = write(profiled(read()).copy(wavFirst = value))

    private fun update(change: (ProfileSettings) -> ProfileSettings) = activeProfile.let { setProfile(it, change(profile(it))) }

    /** How the active profile records, with [wavFirst]. Setting it sets the profile's codec and [wavFirst] (not for WAV). */
    var audioFormat: AudioFormat
        get() = profile(activeProfile).codec.format(wavFirst)
        set(value) {
            update { it.copy(codec = Codec.of(value)) }
            if (value != AudioFormat.Wav) wavFirst = value.wavFirst
        }

    /** The active profile's FLAC compression level: 0 the fastest, 8 the smallest ([FLAC_LEVELS]). */
    var flacLevel: Int
        get() = profile(activeProfile).flacLevel
        set(value) = update { it.copy(flacLevel = value.coerceIn(FLAC_LEVELS)) }

    /** The active profile's FLAC sample rate, channels and bit depth. */
    var flac: FlacFormat
        get() = profile(activeProfile).flac
        set(value) = update { it.copy(flac = value.valid()) }

    /** The active profile's AAC settings. */
    var aac: AacSettings
        get() = profile(activeProfile).aac
        set(value) = update { it.copy(aac = value.valid()) }

    /** [profile]'s settings with the timing, as one value ([RecordingChoice]); Music's always as it records. */
    fun recordingChoice(profile: QualityProfile = activeProfile): RecordingChoice = profile(profile).let { p ->
        read().let { RecordingChoice(p.codec.format(wavFirst && profile != QualityProfile.Music), p.aac, p.flac, it.encodeAfter, it.transcribeWhen) }
    }

    /** The active profile and timing as one value; setting it keeps [RecordingChoice]'s codec, sound and timing. */
    var recordingChoice: RecordingChoice
        get() = recordingChoice(activeProfile)
        set(value) = setChoice(activeProfile, value)

    /** Sets [profile]'s codec and sound from [value], and the timing ([wavFirst], encode and transcribe) for all. */
    fun setChoice(profile: QualityProfile, value: RecordingChoice) {
        setProfile(profile, profile(profile).copy(codec = Codec.of(value.audioFormat), aac = value.aac.valid(), flac = value.flac.valid()))
        write(profiled(read()).copy(
            encodeAfter = value.encodeAfter, transcribeWhen = value.transcribeWhen,
            wavFirst = if (value.audioFormat == AudioFormat.Wav) wavFirst else value.audioFormat.wavFirst,
        ))
    }

    /** Sets the timing alone: WAV first, encode after, transcribe When (the battery). */
    fun setTiming(wavFirst: Boolean, encodeAfter: EncodeAfter, transcribeWhen: EncodeAfter) =
        write(profiled(read()).copy(wavFirst = wavFirst, encodeAfter = encodeAfter, transcribeWhen = transcribeWhen))

    /** When a WAV waiting for FLAC or AAC is encoded: after Stop, or on the charger above [ENCODE_MIN_BATTERY]%. */
    var encodeAfter: EncodeAfter
        get() = read().encodeAfter
        set(value) = write(read().copy(encodeAfter = value))

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

    /**
     * When a moment recorded with "After" on the Record bar is transcribed: [EncodeAfter.Stop] right after it ends,
     * [EncodeAfter.Charging] (the default) once the phone is on the charger with [ENCODE_MIN_BATTERY]% or more.
     */
    var transcribeWhen: EncodeAfter
        get() = read().transcribeWhen
        set(value) = write(read().copy(transcribeWhen = value))

    /**
     * The Record bar's Live or After: live transcription while a moment records, or (off, the default; owner's choice,
     * Nov 15, 2026, to save the battery) the moment is only recorded and transcribed after it stops with its
     * language's improve model: at once on the charger, else when the phone next charges or the user asks.
     */
    var recordLive: Boolean
        get() = read().recordLive
        set(value) = write(read().copy(recordLive = value))

    /** The Record bar's choice, also what the Quick Settings tile records with: Live, After or Only record. */
    var recordMode: RecordMode
        get() = read().let { if (it.recordMusic) RecordMode.Music else if (it.recordOnly) RecordMode.Only else if (it.recordLive) RecordMode.Live else RecordMode.After }
        set(value) = write(read().copy(recordMusic = value == RecordMode.Music, recordOnly = value == RecordMode.Only, recordLive = value == RecordMode.Live))

    /**
     * The notification while recording is as quiet as Android lets it be (owner's request, Nov 15, 2026): the app's
     * name only, silent, not on the lock screen, without the clock or Pause, Mark and Stop. Android keeps one entry for
     * an app that records in the background; it disappears altogether only when the app's notifications are turned off
     * in the phone's settings, which the screen links to.
     */
    var quietRecording: Boolean
        get() = read().quietRecording
        set(value) = write(read().copy(quietRecording = value))

    /**
     * The microphone to record from; null: the phone's own (stereo). When it isn't connected as a moment starts, the
     * phone's own records it and the screen says so.
     */
    var micInput: com.chencohensolution.transcriptor.recording.MicInput?
        get() = profile(activeProfile).micInput
        set(value) = update { it.copy(micInput = value) }

    /** Stop takes two taps, in the recording screens and on the Quick Settings tile; off (the default): one (owner's choice, Nov 15, 2026). */
    var stopTwice: Boolean
        get() = read().stopTwice
        set(value) = write(read().copy(stopTwice = value))

    var lockCalls: Boolean
        get() = read().lockCalls
        set(value) = write(read().copy(lockCalls = value))

    var lockVoiceMessages: Boolean
        get() = read().lockVoiceMessages
        set(value) = write(read().copy(lockVoiceMessages = value))

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

    /** How the recording screen draws the sound level; [LevelMeterStyle.Bars] by default. */
    var levelMeter: LevelMeterStyle
        get() = LevelMeterStyle.of(read().levelMeter)
        set(value) = write(read().copy(levelMeter = value.key))

    /** How readily [modelId] starts a new speaker: -1 fewer speakers, 1 more, 0 its tuned thresholds. */
    fun speakerTuning(modelId: String): Float = read().speakerTuning[modelId] ?: DEFAULT_SPEAKER_TUNING[modelId] ?: 0f

    fun setSpeakerTuning(modelId: String, more: Float) = write(read().let {
        // Kept even at 0: a model with a default of its own ([DEFAULT_SPEAKER_TUNING]) can be set back to the middle.
        it.copy(speakerTuning = it.speakerTuning + (modelId to more.coerceIn(-1f, 1f)))
    })

    /** Advanced mode (Settings › Advanced): the main screen's Record bar also shows Quality and Battery; off by default. */
    var advancedMode: Boolean
        get() = read().advancedMode
        set(value) = write(read().copy(advancedMode = value))

    /** First run's "Get your models" was answered: it isn't offered again ([DefaultModels.offerFirstRun]). */
    var firstRunDone: Boolean
        get() = read().firstRunDone
        set(value) = write(read().copy(firstRunDone = value))

    /**
     * Makes [defaults] the models [language] uses, for the jobs nothing was chosen for yet: a choice the user made in
     * Settings stays.
     */
    fun useDefaults(language: String, defaults: DefaultModels.Defaults) = write(read().let { s ->
        s.copy(
            liveModels = defaults.live?.takeIf { language !in s.liveModels }?.let { s.liveModels + (language to it.id) } ?: s.liveModels,
            refineModels = defaults.improve?.takeIf { language !in s.refineModels }?.let { s.refineModels + (language to it.id) } ?: s.refineModels,
            voiceModel = s.voiceModel ?: defaults.voicePrints.id,
            segmentationModel = s.segmentationModel ?: defaults.segmentation.id,
        )
    })

    /** The live model chosen for [language]; null when none was chosen (the first ready one in the catalog runs). */
    fun liveModel(language: String): String? = read().liveModels[language]

    fun setLiveModel(language: String, modelId: String) = write(read().let { it.copy(liveModels = it.liveModels + (language to modelId)) })

    /** The improve model chosen for [language]; null when none was chosen (the first ready one in [ModelChoices]). */
    fun refineModel(language: String): String? = read().refineModels[language]

    fun setRefineModel(language: String, modelId: String) = write(read().let { it.copy(refineModels = it.refineModels + (language to modelId)) })

    /**
     * Forgets [modelId] wherever it was chosen (live and improve per language, voice prints, speaker turns), when it
     * is removed or its download deleted: each job then uses its default or first ready model.
     */
    fun forgetModel(modelId: String) = write(read().let { s ->
        s.copy(
            liveModels = s.liveModels.filterValues { it != modelId },
            refineModels = s.refineModels.filterValues { it != modelId },
            voiceModel = s.voiceModel.takeIf { it != modelId },
            segmentationModel = s.segmentationModel.takeIf { it != modelId },
        )
    })

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

/** How a moment records: transcribed as it records, after it stops, or not at all until the user asks. */
enum class RecordMode {
    Live,
    /** Recorded, then transcribed with the improve model: at once on the charger, else when the phone next charges. */
    After,
    /** Recorded only: it waits for "Transcribe now" on the moment. */
    Only,

    /**
     * A concert: the [QualityProfile.Music] profile, encoded as it records (FLAC by default; its own settings, not the active
     * profile's), the microphone without voice processing, and no transcript (like [Only]).
     */
    Music,
}
