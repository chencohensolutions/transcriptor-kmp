package com.chencohensolution.transcriptor.models

/** How a model runs. */
@kotlinx.serialization.Serializable
enum class ModelKind {
    /** Whisper (whisper.cpp, one ggml file): transcribes a line at a time, with punctuation. */
    Whisper,

    /**
     * Whisper compiled for one Qualcomm NPU (sherpa-onnx's QNN backend: encoder and decoder context binaries, tokens):
     * a line at a time like [Whisper], several times faster and on less battery, on the chips in [SpeechModel.chips].
     */
    WhisperNpu,

    /** A streaming transducer (sherpa-onnx: encoder, decoder, joiner, tokens): words as the audio arrives. */
    StreamingTransducer,

    /**
     * A streaming transducer compiled for one Qualcomm NPU (sherpa-onnx's QNN backend: encoder, decoder and joiner
     * context binaries, tokens): like [StreamingTransducer], on the chips in [SpeechModel.chips].
     */
    StreamingTransducerNpu,

    /** Voice prints for telling speakers apart (sherpa-onnx speaker embedding): not a speech model. */
    SpeakerEmbedding,

    /** Who speaks when, frame by frame, overlaps included (pyannote segmentation on sherpa-onnx): not a speech model. */
    SpeakerSegmentation,
    ;

    /** Turns speech into text. */
    val transcribes get() = this == Whisper || this == WhisperNpu || this == StreamingTransducer || this == StreamingTransducerNpu
}

/**
 * One file of a model, downloaded from [url] (pinned to a commit) and checked against [sha256]. A small file added
 * from Hugging Face, which the Hub stores without a sha256, is checked against its git blob id ([gitSha1]) instead.
 */
data class ModelFile(val name: String, val url: String, val sha256: String, val sizeBytes: Long, val gitSha1: String? = null)

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
    /** The chips it runs on (Android's `Build.SOC_MODEL`, "SM8750"); empty for any phone. */
    val chips: List<String> = emptyList(),
    /**
     * Copied to the phone by hand (its folder with an empty `.ok`), not downloaded: a model the catalog may not offer
     * (a non-commercial license) or that has no public home yet. Its files' urls are empty; it is listed once there.
     */
    val manual: Boolean = false,
) {
    val sizeBytes get() = files.sumOf { it.sizeBytes }

    fun runsOn(chip: String?) = chips.isEmpty() || chip in chips

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
    // ivritTurbo (commit f33172a8) compiled with QAIRT 2.40.0.251030 for the Snapdragon 8 Elite's NPU (Hexagon v79):
    // 16-bit, encoder and decoder graphs as sherpa-onnx's Whisper QNN export makes them. Not published yet (Nov 4,
    // 2026): until it is on Hugging Face the files are copied to the phone by hand.
    private const val NPU_SM8750 = "https://huggingface.co/hencohen83/ivrit-whisper-large-v3-turbo-qnn-sm8750/resolve/d5997a9642f2dc7ca388f4c4eaf527c2b2fda218"
    val ivritTurboNpu = SpeechModel(
        id = "ivrit-whisper-large-v3-turbo-npu-sm8750",
        name = "ivrit.ai Whisper large-v3 turbo · NPU",
        languages = listOf("he"),
        parameters = "809M",
        license = "Apache-2.0",
        fitsLive = true,
        source = "https://huggingface.co/ivrit-ai/whisper-large-v3-turbo",
        kind = ModelKind.WhisperNpu,
        files = listOf(
            ModelFile("encoder.bin", "$NPU_SM8750/encoder.bin", "b9a00e6db9f37f6167511cf2a21506c8b395b9a477acf13f4b92b2e09a213609", 1_346_412_544),
            ModelFile("decoder.bin", "$NPU_SM8750/decoder.bin", "914f8c2f37e88c10b0909f3f1d768e7484691cc29384e31d325d01f7013ded8a", 452_820_992),
            ModelFile("tokens.txt", "$NPU_SM8750/tokens.txt", "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", 816_730),
        ),
        chips = listOf("SM8750"),
    )
    // ivritLargeV3 (commit 766847c9) compiled the same way, for the refine pass only: 16-bit weights
    // (`--float_bitwidth 16`; at 32-bit the encoder's library is over 2 GB and won't link) and no ONNX simplification
    // (it fails on graphs over 2 GB). Not published yet (Nov 5, 2026): copied to the phone by hand.
    private const val LARGE_NPU_SM8750 = "https://huggingface.co/hencohen83/ivrit-whisper-large-v3-qnn-sm8750/resolve/a5c541a9ce0ea9b8fa4754808d5f6ab8720353bc"
    val ivritLargeV3Npu = SpeechModel(
        id = "ivrit-whisper-large-v3-npu-sm8750",
        name = "ivrit.ai Whisper large-v3 · NPU",
        languages = listOf("he"),
        parameters = "1.55B",
        license = "Apache-2.0",
        fitsLive = false,
        source = "https://huggingface.co/ivrit-ai/whisper-large-v3",
        kind = ModelKind.WhisperNpu,
        files = listOf(
            ModelFile("encoder.bin", "$LARGE_NPU_SM8750/encoder.bin", "f6050d1bbcc885dd6b79284dd767b526044ff9148f75a903e7e84995a10ad1d5", 1_526_292_480),
            ModelFile("decoder.bin", "$LARGE_NPU_SM8750/decoder.bin", "db4bacf5c2023cb97f0ab26dd2c05d2d1260053b93b3f69005d7cf0783cbe229", 1_751_195_648),
            ModelFile("tokens.txt", "$LARGE_NPU_SM8750/tokens.txt", "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", 816_730),
        ),
        chips = listOf("SM8750"),
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

    // ivritZipformer's own checkpoint (model.pt, commit ee769a2a; not its int8 files) exported by icefall's
    // export-onnx-streaming.py (fixed shapes, chunk 64 = 1.28 s, left context 256) and compiled with QAIRT 2.40.0.251030
    // for the Snapdragon 8 Elite's NPU, 16-bit. Published on the owner's Hugging Face (Nov 6, 2026).
    private const val ZIPFORMER_NPU_SM8750 = "https://huggingface.co/hencohen83/zipformer2-66m-streaming-he-qnn-sm8750/resolve/284f0a333ca456ef9c4cac0c889c0b757355e13f"
    val ivritZipformerNpu = SpeechModel(
        id = "ivrit-zipformer2-66m-streaming-npu-sm8750",
        name = "ivrit.ai Zipformer streaming · NPU",
        languages = listOf("he"),
        parameters = "66M",
        license = "CC-BY-4.0",
        fitsLive = true,
        source = "https://huggingface.co/ivrit-ai/zipformer2-66m-streaming-he",
        kind = ModelKind.StreamingTransducerNpu,
        files = listOf(
            ModelFile("encoder.bin", "$ZIPFORMER_NPU_SM8750/encoder.bin", "f0bb5dea5abe4d07a81ee14a2b88004a59cd4caa14ba0f3a61700addff82354e", 154_628_096),
            ModelFile("decoder.bin", "$ZIPFORMER_NPU_SM8750/decoder.bin", "4d35145713eb9c5d1aebfbf5a7843c30eee259e331b0763f20442c5a4d654d0c", 2_179_072),
            ModelFile("joiner.bin", "$ZIPFORMER_NPU_SM8750/joiner.bin", "1a089ae557b2321812a7dd3bede4346d8ee9ad7bc7d655a00a266364e856b0b9", 1_601_536),
            ModelFile("tokens.txt", "$ZIPFORMER_NPU_SM8750/tokens.txt", "e4ebb5e72a6aeef47437a55fa2308ebbedb7c51c557de7a89bf965c23ea9af4a", 17_086),
        ),
        credit = "zipformer2-66m-streaming-he by Yakov Kolani, ivrit.ai (CC-BY-4.0)",
        chips = listOf("SM8750"),
    )

    /** Voice prints for speaker labels, in every language. Not in [all]: it doesn't transcribe. */
    val voicePrints = SpeechModel(
        id = "3dspeaker-campplus-zh-en-advanced",
        name = "3D-Speaker CAM++",
        languages = listOf(SpeechModel.MULTILINGUAL),
        parameters = "7M",
        license = "Apache-2.0",
        fitsLive = true,
        source = "https://github.com/modelscope/3D-Speaker",
        kind = ModelKind.SpeakerEmbedding,
        files = listOf(
            ModelFile(
                "model.onnx",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
                "aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2", 28_281_164,
            ),
        ),
    )

    /** 3D-Speaker ERes2NetV2 (Chinese training, 17.8M): another voice-print model to pick. Not in [all]. */
    val voicePrintsEres2NetV2 = SpeechModel(
        id = "3dspeaker-eres2netv2-zh-cn",
        name = "3D-Speaker ERes2NetV2",
        languages = listOf(SpeechModel.MULTILINGUAL),
        parameters = "18M",
        license = "Apache-2.0",
        fitsLive = true,
        source = "https://github.com/modelscope/3D-Speaker",
        kind = ModelKind.SpeakerEmbedding,
        files = listOf(
            ModelFile(
                "model.onnx",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx",
                "bf1a75b9930474cf3389ef415e6e5d38ca96fea4a3a00f7e301d080a58ee2239", 71_441_526,
            ),
        ),
    )

    /**
     * WeSpeaker ResNet293-LM (VoxCeleb, 28.6M; CC-BY-4.0), from the owner's Hugging Face: sherpa-onnx's release with
     * `feature_normalize_type` `global-mean` added (WeSpeaker trains on mean-normalized features; without it its prints
     * are much worse), and its NPU build for the Snapdragon 8 Elite (`t150.bin`, `t300.bin`, `t600.bin`, used on that
     * chip only; docs/npu-build.md). Not in [all].
     */
    private const val RESNET293 = "https://huggingface.co/hencohen83/wespeaker-resnet293-lm-qnn-sm8750/resolve/75258f7acf21cff8478ee219a06d82eafdf4b630"
    val voicePrintsResNet293 = SpeechModel(
        id = "wespeaker-resnet293-lm",
        name = "WeSpeaker ResNet293-LM",
        languages = listOf(SpeechModel.MULTILINGUAL),
        parameters = "29M",
        license = "CC-BY-4.0",
        fitsLive = true,
        source = "https://github.com/wenet-e2e/wespeaker",
        kind = ModelKind.SpeakerEmbedding,
        files = listOf(
            ModelFile("model.onnx", "$RESNET293/model.onnx", "2aabeca5e19cc249b2cffe709953ca7302e7eb6477b9e2ce81ab919be7e9ddcd", 114_336_566),
            ModelFile("t150.bin", "$RESNET293/t150.bin", "7638b878bf4412ec0412bf3e30c3f2ec14bdcf4c778ff71a2eaaef9bfa7cb70b", 58_650_624),
            ModelFile("t300.bin", "$RESNET293/t300.bin", "79baafcf3c9623af83a13107274c876f697f03b448c2b00e3e32a0318fc8b8e1", 59_072_512),
            ModelFile("t600.bin", "$RESNET293/t600.bin", "59a4c6c75c663989113b2c1aac7398067786e90c82f977a97f4e115a76de9e7d", 59_961_344),
        ),
        credit = "WeSpeaker (wenet-e2e), trained on VoxCeleb (CC-BY-4.0)",
    )

    /**
     * WeSpeaker SimAM-ResNet100 (VoxBlink2, fine-tuned on VoxCeleb2; 50.2M), copied by hand: non-commercial
     * (CC-BY-NC-SA-4.0, VoxBlink2's), so never offered here. The ONNX with mean normalization, and its NPU build
     * as for [voicePrintsResNet293]. Not in [all].
     */
    val voicePrintsSimAmResNet100 = SpeechModel(
        id = "wespeaker-samresnet100-voxblink2",
        name = "WeSpeaker SimAM-ResNet100",
        languages = listOf(SpeechModel.MULTILINGUAL),
        parameters = "50M",
        license = "CC-BY-NC-SA-4.0",
        fitsLive = true,
        source = "https://github.com/wenet-e2e/wespeaker/blob/master/docs/pretrained.md",
        kind = ModelKind.SpeakerEmbedding,
        // Published (non-commercial), copied by hand: https://huggingface.co/hencohen83/wespeaker-samresnet100-voxblink2-qnn-sm8750
        files = listOf(ModelFile("model.onnx", "", "094f9d4476a61c1595a0c01bd26a9aa80d98c8b5398f2f57334a41b4e0701e0c", 201_116_099)),
        credit = "WeSpeaker (wenet-e2e), trained on VoxBlink2 and VoxCeleb2 (CC-BY-NC-SA-4.0: not for commercial use)",
        manual = true,
    )

    /** The voice-print models to pick from, today's default first. */
    val voicePrintModels get() = listOf(voicePrints, voicePrintsEres2NetV2, voicePrintsResNet293, voicePrintsSimAmResNet100)

    /** pyannote's segmentation 3.0, for the speaker pass after the lecture (with [voicePrints]). Not in [all]. */
    val speakerSegmentation = SpeechModel(
        id = "pyannote-segmentation-3.0",
        name = "pyannote segmentation 3.0",
        languages = listOf(SpeechModel.MULTILINGUAL),
        parameters = "1.5M",
        license = "MIT",
        fitsLive = false,
        source = "https://huggingface.co/pyannote/segmentation-3.0",
        kind = ModelKind.SpeakerSegmentation,
        // sherpa-onnx's ONNX export, by its author.
        files = listOf(
            ModelFile(
                "model.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/9403a6902bb58e3d5ae8c7e77c3422de279db2e0/model.onnx",
                "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079", 5_992_913,
            ),
        ),
    )

    /** Their NPU builds are published (non-commercial) at `https://huggingface.co/hencohen83/<id>-qnn-sm8750`. */
    private fun diarizen(id: String, name: String, parameters: String, license: String, credit: String) = SpeechModel(
        id = id, name = name, languages = listOf(SpeechModel.MULTILINGUAL), parameters = parameters, license = license,
        fitsLive = true, source = "https://huggingface.co/BUT-FIT/$id", kind = ModelKind.SpeakerSegmentation,
        files = listOf(ModelFile("model.onnx", "", "", 0)), credit = credit, manual = true,
    )

    /**
     * BUT-FIT's DiariZen (WavLM + Conformer) segmentation models, for live speaker turns in place of pyannote's,
     * copied by hand: `model.onnx` (exported with a fixed chunk and pyannote's metadata, so sherpa-onnx loads it) and
     * on the Snapdragon 8 Elite `window.bin`, its NPU build. Up to 4 speakers per chunk, 2 at once, 20 ms frames.
     */
    val diarizenBaseS80 = diarizen(
        "diarizen-wavlm-base-s80-md", "DiariZen WavLM Base s80", "25M", "CC-BY-NC-4.0",
        "DiariZen by BUT-FIT (CC-BY-NC-4.0: not for commercial use); 16 s chunks",
    )
    val diarizenLargeS80 = diarizen(
        "diarizen-wavlm-large-s80-md", "DiariZen WavLM Large s80", "70M", "CC-BY-NC-4.0",
        "DiariZen by BUT-FIT (CC-BY-NC-4.0: not for commercial use); 16 s chunks",
    )

    /** Its Hugging Face page says MIT, DiariZen's repository says all its weights are CC-BY-NC-4.0: treated as the latter. */
    val diarizenMeetingBase = diarizen(
        "diarizen-meeting-base", "DiariZen meeting Base", "101M", "CC-BY-NC-4.0",
        "DiariZen by BUT-FIT (MIT on Hugging Face, CC-BY-NC-4.0 in its repository: treated as non-commercial); 8 s chunks",
    )

    /** The segmentation models for speaker turns, the default (pyannote) first. */
    val segmentationModels get() = listOf(speakerSegmentation, diarizenBaseS80, diarizenLargeS80, diarizenMeetingBase)

    val all = listOf(ivritTurboNpu, ivritTurbo, ivritZipformerNpu, ivritZipformer, ivritLargeV3Npu, ivritLargeV3, whisperTurbo, whisperLargeV3)

    /** A model's name by its id (a line's or a version's model), or the id when the catalog no longer has it. */
    fun nameOf(id: String): String = (all + voicePrintModels + segmentationModels).firstOrNull { it.id == id }?.name ?: id

    /**
     * The models that know [language] and run on [chip] (the phone's): the ones made for it first, then the
     * multilingual ones.
     */
    fun forLanguage(language: String, chip: String?): List<SpeechModel> =
        all.filter { it.runsOn(chip) }.let { models ->
            models.filter { language in it.languages } + models.filter { SpeechModel.MULTILINGUAL in it.languages }
        }
}
