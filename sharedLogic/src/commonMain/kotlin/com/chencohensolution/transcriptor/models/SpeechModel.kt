package com.chencohensolution.transcriptor.models

/**
 * A speech recognition model the app can download and run on the phone (whisper.cpp, quantized ggml files).
 * Which one runs is chosen per language in Settings › Models: one for live transcription during the lecture, one
 * for refining after it. Downloads are to be pinned to a commit with their sha256 when the downloader is built.
 */
data class SpeechModel(
    val id: String,
    val name: String,
    /** BCP-47 tags it is good at; a multilingual model lists [MULTILINGUAL]. */
    val languages: List<String>,
    val parameters: String,
    /** Approximate download size of the quantized file. */
    val sizeBytes: Long,
    val license: String,
    val fitsLive: Boolean,
    val source: String,
) {
    companion object {
        const val MULTILINGUAL = "*"
    }
}

/** The models offered in Settings › Models. Every one is free for commercial use. */
object ModelCatalog {
    val ivritLargeV3 = SpeechModel(
        id = "ivrit-whisper-large-v3",
        name = "ivrit.ai Whisper large-v3",
        languages = listOf("he"),
        parameters = "1.55B",
        sizeBytes = 1_080_000_000,
        license = "Apache-2.0",
        fitsLive = false,
        source = "https://huggingface.co/ivrit-ai/whisper-large-v3",
    )
    val ivritTurbo = SpeechModel(
        id = "ivrit-whisper-large-v3-turbo",
        name = "ivrit.ai Whisper large-v3 turbo",
        languages = listOf("he"),
        parameters = "809M",
        sizeBytes = 574_000_000,
        license = "Apache-2.0",
        fitsLive = true,
        source = "https://huggingface.co/ivrit-ai/whisper-large-v3-turbo",
    )
    val whisperLargeV3 = SpeechModel(
        id = "whisper-large-v3",
        name = "Whisper large-v3",
        languages = listOf(SpeechModel.MULTILINGUAL),
        parameters = "1.55B",
        sizeBytes = 1_080_000_000,
        license = "MIT",
        fitsLive = false,
        source = "https://huggingface.co/openai/whisper-large-v3",
    )
    val whisperTurbo = SpeechModel(
        id = "whisper-large-v3-turbo",
        name = "Whisper large-v3 turbo",
        languages = listOf(SpeechModel.MULTILINGUAL),
        parameters = "809M",
        sizeBytes = 574_000_000,
        license = "MIT",
        fitsLive = true,
        source = "https://huggingface.co/openai/whisper-large-v3-turbo",
    )

    val all = listOf(ivritTurbo, ivritLargeV3, whisperTurbo, whisperLargeV3)

    /** The models that know [language]: the ones made for it first, then the multilingual ones. */
    fun forLanguage(language: String): List<SpeechModel> =
        all.filter { language in it.languages } + all.filter { SpeechModel.MULTILINGUAL in it.languages }
}
