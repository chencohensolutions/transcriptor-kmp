package com.chencohensolution.transcriptor.settings

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
    /** The passes after the lecture: the speaker pass when it stops, and the refine pass on its review screen. */
    val afterLecture: Boolean = true,
)

/** The app's settings, in `settings.json` under [root]. */
class SettingsStore(private val fileSystem: FileSystem, private val root: Path) {
    private val file get() = root / "settings.json"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    var audioFormat: AudioFormat
        get() = read().audioFormat
        set(value) = write(read().copy(audioFormat = value))

    /**
     * The passes after the lecture are offered: the speaker pass runs when it stops, and its review screen can refine
     * it. Off: the lecture keeps its live transcript (the speakers can still be found from the review screen).
     */
    var afterLecture: Boolean
        get() = read().afterLecture
        set(value) = write(read().copy(afterLecture = value))

    /** The live model chosen for [language]; null when none was chosen (the first ready one in the catalog runs). */
    fun liveModel(language: String): String? = read().liveModels[language]

    fun setLiveModel(language: String, modelId: String) = write(read().let { it.copy(liveModels = it.liveModels + (language to modelId)) })

    private fun read(): SettingsFileV1 =
        if (fileSystem.exists(file)) json.decodeFromString(fileSystem.read(file) { readUtf8() }) else SettingsFileV1()

    private fun write(settings: SettingsFileV1) {
        fileSystem.createDirectories(root)
        val temp = root / "settings.json.tmp"
        fileSystem.write(temp) { writeUtf8(json.encodeToString(settings)) }
        fileSystem.atomicMove(temp, file)
    }
}
