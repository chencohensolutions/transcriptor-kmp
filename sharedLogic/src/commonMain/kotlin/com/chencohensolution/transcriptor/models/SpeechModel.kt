package com.chencohensolution.transcriptor.models

/**
 * A speech recognition model the app can download and run on the phone (whisper.cpp ggml files). Which one runs is
 * chosen per language in Settings › Models: one for live transcription during the lecture, one for refining after
 * it. The file is downloaded from [fileUrl], pinned to a commit, and checked against [sha256].
 */
data class SpeechModel(
    val id: String,
    val name: String,
    /** BCP-47 tags it is good at; a multilingual model lists [MULTILINGUAL]. */
    val languages: List<String>,
    val parameters: String,
    /** The download's exact size. */
    val sizeBytes: Long,
    val license: String,
    val fitsLive: Boolean,
    /** The model's page. */
    val source: String,
    /** The ggml file, at a fixed commit. */
    val fileUrl: String,
    val sha256: String,
    /**
     * A ggml type to shrink the download to on the phone ("q8_0"), for a model published only in full precision:
     * smaller and about twice as fast, with the same text in our tests. Null keeps the file as downloaded.
     */
    val quantizeTo: String? = null,
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
        sizeBytes = 3_095_033_483, // f16: ivrit.ai publishes no quantized file; q8_0 on the phone.
        license = "Apache-2.0",
        fitsLive = false,
        source = "https://huggingface.co/ivrit-ai/whisper-large-v3",
        fileUrl = "https://huggingface.co/ivrit-ai/whisper-large-v3-ggml/resolve/9ead614052ce13dfe5f8d0f6cd3e36787a9cf60c/ggml-model.bin",
        sha256 = "09e66ec67b2e00c6933afab6684cbf78fe023e8ad153c1848f62000e4335a07f",
        quantizeTo = "q8_0",
    )
    val ivritTurbo = SpeechModel(
        id = "ivrit-whisper-large-v3-turbo",
        name = "ivrit.ai Whisper large-v3 turbo",
        languages = listOf("he"),
        parameters = "809M",
        sizeBytes = 1_624_555_275, // f16: ivrit.ai publishes no quantized file; q8_0 on the phone (874 MB).
        license = "Apache-2.0",
        fitsLive = true,
        source = "https://huggingface.co/ivrit-ai/whisper-large-v3-turbo",
        fileUrl = "https://huggingface.co/ivrit-ai/whisper-large-v3-turbo-ggml/resolve/2130c78e4a9cb4914cc4df91a1c3031407789705/ggml-model.bin",
        sha256 = "c8090411113357097bfafc2b8e228ec1639fa7f5fe4ecb5d054ac0ccef8641b1",
        quantizeTo = "q8_0",
    )
    val whisperLargeV3 = SpeechModel(
        id = "whisper-large-v3",
        name = "Whisper large-v3",
        languages = listOf(SpeechModel.MULTILINGUAL),
        parameters = "1.55B",
        sizeBytes = 1_081_140_203, // q5_0
        license = "MIT",
        fitsLive = false,
        source = "https://huggingface.co/openai/whisper-large-v3",
        fileUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-large-v3-q5_0.bin",
        sha256 = "d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1",
    )
    val whisperTurbo = SpeechModel(
        id = "whisper-large-v3-turbo",
        name = "Whisper large-v3 turbo",
        languages = listOf(SpeechModel.MULTILINGUAL),
        parameters = "809M",
        sizeBytes = 574_041_195, // q5_0
        license = "MIT",
        fitsLive = true,
        source = "https://huggingface.co/openai/whisper-large-v3-turbo",
        fileUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-large-v3-turbo-q5_0.bin",
        sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
    )

    val all = listOf(ivritTurbo, ivritLargeV3, whisperTurbo, whisperLargeV3)

    /** The models that know [language]: the ones made for it first, then the multilingual ones. */
    fun forLanguage(language: String): List<SpeechModel> =
        all.filter { language in it.languages } + all.filter { SpeechModel.MULTILINGUAL in it.languages }
}
