package com.chencohensolution.transcriptor.models

/** How a model runs. */
enum class ModelKind {
    /** Whisper (whisper.cpp, one ggml file): transcribes a line at a time, with punctuation. */
    Whisper,

    /** A streaming transducer (sherpa-onnx: encoder, decoder, joiner, tokens): words as the audio arrives. */
    StreamingTransducer,
}

/** One file of a model, downloaded from [url] (pinned to a commit) and checked against [sha256]. */
data class ModelFile(val name: String, val url: String, val sha256: String, val sizeBytes: Long)

/**
 * A speech recognition model the app can download and run on the phone. Which one runs is chosen per language: one
 * for live transcription during the lecture, one for refining after it.
 */
data class SpeechModel(
    val id: String,
    val name: String,
    /** BCP-47 tags it is good at; a multilingual model lists [MULTILINGUAL]. */
    val languages: List<String>,
    val parameters: String,
    val license: String,
    val fitsLive: Boolean,
    /** The model's page. */
    val source: String,
    val kind: ModelKind,
    val files: List<ModelFile>,
    /** The attribution its license asks for, shown with it. */
    val credit: String? = null,
    /**
     * A ggml type to shrink the download to on the phone ("q8_0"), for a model published only in full precision:
     * smaller and about twice as fast, with the same text in our tests. Null keeps the file as downloaded.
     */
    val quantizeTo: String? = null,
) {
    val sizeBytes get() = files.sumOf { it.sizeBytes }

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
        license = "Apache-2.0",
        fitsLive = false,
        source = "https://huggingface.co/ivrit-ai/whisper-large-v3",
        kind = ModelKind.Whisper,
        // f16: ivrit.ai publishes no quantized file; q8_0 on the phone.
        files = listOf(ModelFile("model.bin", "https://huggingface.co/ivrit-ai/whisper-large-v3-ggml/resolve/9ead614052ce13dfe5f8d0f6cd3e36787a9cf60c/ggml-model.bin", "09e66ec67b2e00c6933afab6684cbf78fe023e8ad153c1848f62000e4335a07f", 3_095_033_483)),
        quantizeTo = "q8_0",
    )
    val ivritTurbo = SpeechModel(
        id = "ivrit-whisper-large-v3-turbo",
        name = "ivrit.ai Whisper large-v3 turbo",
        languages = listOf("he"),
        parameters = "809M",
        license = "Apache-2.0",
        fitsLive = true,
        source = "https://huggingface.co/ivrit-ai/whisper-large-v3-turbo",
        kind = ModelKind.Whisper,
        // f16: ivrit.ai publishes no quantized file; q8_0 on the phone (874 MB).
        files = listOf(ModelFile("model.bin", "https://huggingface.co/ivrit-ai/whisper-large-v3-turbo-ggml/resolve/2130c78e4a9cb4914cc4df91a1c3031407789705/ggml-model.bin", "c8090411113357097bfafc2b8e228ec1639fa7f5fe4ecb5d054ac0ccef8641b1", 1_624_555_275)),
        quantizeTo = "q8_0",
    )
    val whisperLargeV3 = SpeechModel(
        id = "whisper-large-v3",
        name = "Whisper large-v3",
        languages = listOf(SpeechModel.MULTILINGUAL),
        parameters = "1.55B",
        license = "MIT",
        fitsLive = false,
        source = "https://huggingface.co/openai/whisper-large-v3",
        kind = ModelKind.Whisper,
        // q5_0
        files = listOf(ModelFile("model.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-large-v3-q5_0.bin", "d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1", 1_081_140_203)),
    )
    val whisperTurbo = SpeechModel(
        id = "whisper-large-v3-turbo",
        name = "Whisper large-v3 turbo",
        languages = listOf(SpeechModel.MULTILINGUAL),
        parameters = "809M",
        license = "MIT",
        fitsLive = true,
        source = "https://huggingface.co/openai/whisper-large-v3-turbo",
        kind = ModelKind.Whisper,
        // q5_0
        files = listOf(ModelFile("model.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-large-v3-turbo-q5_0.bin", "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", 574_041_195)),
    )

    private const val ZIPFORMER = "https://huggingface.co/ivrit-ai/zipformer2-66m-streaming-he/resolve/ee769a2a13b887f91572eec349458bfb0fe79e7a/sherpa-onnx"
    val ivritZipformer = SpeechModel(
        id = "ivrit-zipformer2-66m-streaming",
        name = "ivrit.ai Zipformer streaming",
        languages = listOf("he"),
        parameters = "66M",
        license = "CC-BY-4.0",
        fitsLive = true,
        source = "https://huggingface.co/ivrit-ai/zipformer2-66m-streaming-he",
        kind = ModelKind.StreamingTransducer,
        files = listOf(
            ModelFile("encoder.int8.onnx", "$ZIPFORMER/encoder.int8.onnx", "6c6febcb74a09435315a1d872a6539f6e7fc2c333533f14c74edc0e85c49ffcd", 70_478_964),
            ModelFile("decoder.int8.onnx", "$ZIPFORMER/decoder.int8.onnx", "feb03e7c8aa761593a9284a6fa1e91259be3702de75f630062256c1a59c5a906", 1_049_105),
            ModelFile("joiner.int8.onnx", "$ZIPFORMER/joiner.int8.onnx", "1d2c6d8e6dabc5eb52de52e95718e9bdfbffbfe7ead3808fb5ffd74e83bd9cae", 771_805),
            ModelFile("tokens.txt", "$ZIPFORMER/tokens.txt", "e4ebb5e72a6aeef47437a55fa2308ebbedb7c51c557de7a89bf965c23ea9af4a", 17_086),
        ),
        credit = "zipformer2-66m-streaming-he by Yakov Kolani, ivrit.ai (CC-BY-4.0)",
    )

    val all = listOf(ivritTurbo, ivritZipformer, ivritLargeV3, whisperTurbo, whisperLargeV3)

    /** The models that know [language]: the ones made for it first, then the multilingual ones. */
    fun forLanguage(language: String): List<SpeechModel> =
        all.filter { language in it.languages } + all.filter { SpeechModel.MULTILINGUAL in it.languages }
}
