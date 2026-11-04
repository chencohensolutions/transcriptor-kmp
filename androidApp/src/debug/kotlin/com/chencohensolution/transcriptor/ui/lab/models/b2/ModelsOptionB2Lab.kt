package com.chencohensolution.transcriptor.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.transcriptor.models.ModelFile
import com.chencohensolution.transcriptor.models.ModelKind
import com.chencohensolution.transcriptor.models.SpeechModel
import kotlinx.coroutines.delay

// Fakes: everything option b fakes (downloads, the languages, choosing a refine model), and the Hugging Face search:
// a second's wait, then results from the list below matched by words (no network). The repos are real but their
// downloads, likes, commits and sha256 are made up; the two lab-example/ repos are made up whole, for a non-commercial
// license and a missing one. What the app can't run (PyTorch, safetensors) is only a count. Installing adds the model
// to the catalog and starts the fake download; removing takes it off.
internal val modelsOptionB2 = LabOption("b2", "Catalog + models from Hugging Face", "b with a search of Hugging Face for models the app can run, their licenses marked; no storage bar") {
    var added by remember { mutableStateOf(emptyList<SpeechModel>()) }
    val (base, actions) = rememberModelsLab(added)
    var hub by remember { mutableStateOf(HubSearch()) }
    var asked by remember { mutableStateOf<String?>("whisper") }
    LaunchedEffect(asked) {
        val query = asked ?: return@LaunchedEffect
        hub = hub.copy(query = query, searching = true, error = null)
        delay(1_000)
        val found = hubCatalog.filter { m -> matches(m, query) }
        hub = hub.copy(searching = false, results = found, unusable = if (query.contains("whisper", true)) 23 else 7)
        asked = null
    }
    ModelsScreenB2(
        base.copy(added = added), actions, hub,
        HubActions(
            search = { asked = it.trim() },
            install = { m, v ->
                val model = m.toSpeechModel(v)
                if (added.none { it.id == model.id }) added = added + model
                actions.download(model)
            },
            remove = { model -> actions.delete(model); added = added.filter { it.id != model.id } },
        ),
    )
}

/** Every word of [query] is in the repo's name or its languages' names. */
private fun matches(m: HubModel, query: String): Boolean {
    val names = mapOf("he" to "hebrew עברית ivrit", "en" to "english אנגלית", "zh" to "chinese סינית", "ar" to "arabic ערבית", SpeechModel.MULTILINGUAL to "multilingual")
    val text = (m.repo + " " + m.languages.joinToString(" ") { names[it] ?: it } + " " + if (m.kind == ModelKind.Whisper) "whisper ggml" else "streaming zipformer sherpa").lowercase()
    return query.lowercase().split(' ').filter { it.isNotBlank() }.all { it in text }
}

private fun ggml(repo: String, commit: String, label: String, file: String, size: Long, parameters: String, live: Boolean) =
    HubVariant(label, listOf(ModelFile("model.bin", "https://huggingface.co/$repo/resolve/$commit/$file", "0".repeat(64), size)), parameters, live)

private fun streaming(repo: String, commit: String, encoder: Long) = HubVariant(
    "int8",
    listOf("encoder.int8.onnx" to encoder, "decoder.int8.onnx" to 1_049_000L, "joiner.int8.onnx" to 771_000L, "tokens.txt" to 5_000L)
        .map { (f, size) -> ModelFile(f, "https://huggingface.co/$repo/resolve/$commit/$f", "0".repeat(64), size) },
    "70M", true,
)

private const val CPP = "ggerganov/whisper.cpp"
private const val CPP_AT = "5359861c739e955e79d9a303bcbc70fb988958b1"

private val hubCatalog = listOf(
    HubModel(
        CPP, ModelKind.Whisper, "mit", LicenseUse.Commercial, listOf(SpeechModel.MULTILINGUAL), 412_000, 4_100, CPP_AT,
        listOf(
            ggml(CPP, CPP_AT, "large-v3-turbo q5_0", "ggml-large-v3-turbo-q5_0.bin", 574_041_195, "809M", true),
            ggml(CPP, CPP_AT, "large-v3-turbo q8_0", "ggml-large-v3-turbo-q8_0.bin", 874_188_075, "809M", true),
            ggml(CPP, CPP_AT, "small q5_1", "ggml-small-q5_1.bin", 190_085_487, "244M", true),
            ggml(CPP, CPP_AT, "medium q5_0", "ggml-medium-q5_0.bin", 539_212_467, "769M", true),
            ggml(CPP, CPP_AT, "large-v3 q5_0", "ggml-large-v3-q5_0.bin", 1_081_140_203, "1.55B", false),
        ),
    ),
    HubModel(
        "ivrit-ai/whisper-large-v3-turbo-ggml", ModelKind.Whisper, "apache-2.0", LicenseUse.Commercial, listOf("he"), 9_800, 21,
        "2130c78e4a9cb4914cc4df91a1c3031407789705",
        listOf(ggml("ivrit-ai/whisper-large-v3-turbo-ggml", "2130c78e4a9cb4914cc4df91a1c3031407789705", "f16", "ggml-model.bin", 1_624_555_275, "809M", true)),
    ),
    HubModel(
        "ivrit-ai/whisper-large-v3-ggml", ModelKind.Whisper, "apache-2.0", LicenseUse.Commercial, listOf("he"), 3_100, 9,
        "9ead614052ce13dfe5f8d0f6cd3e36787a9cf60c",
        listOf(ggml("ivrit-ai/whisper-large-v3-ggml", "9ead614052ce13dfe5f8d0f6cd3e36787a9cf60c", "f16", "ggml-model.bin", 3_095_033_483, "1.55B", false)),
    ),
    HubModel(
        "lab-example/whisper-small-he-nc", ModelKind.Whisper, "cc-by-nc-4.0", LicenseUse.NonCommercial, listOf("he"), 640, 3,
        "1f0e2d3c4b5a69788796a5b4c3d2e1f0a9b8c7d6",
        listOf(ggml("lab-example/whisper-small-he-nc", "1f0e2d3c4b5a69788796a5b4c3d2e1f0a9b8c7d6", "q5_1", "ggml-small-he-q5_1.bin", 190_100_000, "244M", true)),
    ),
    HubModel(
        "lab-example/whisper-medium-ar", ModelKind.Whisper, null, LicenseUse.Unknown, listOf("ar"), 210, 1,
        "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
        listOf(ggml("lab-example/whisper-medium-ar", "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678", "q5_0", "ggml-medium-ar-q5_0.bin", 539_200_000, "769M", true)),
    ),
    HubModel(
        "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26", ModelKind.StreamingTransducer, "apache-2.0", LicenseUse.Commercial,
        listOf("en"), 18_500, 12, "6f3b5d7a9c1e2f4a6b8d0c2e4f6a8b0d2c4e6f8a",
        listOf(streaming("csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26", "6f3b5d7a9c1e2f4a6b8d0c2e4f6a8b0d2c4e6f8a", 68_700_000)),
    ),
    HubModel(
        "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20", ModelKind.StreamingTransducer, "apache-2.0",
        LicenseUse.Commercial, listOf("zh", "en"), 42_000, 30, "0d1c2b3a4f5e6d7c8b9a0f1e2d3c4b5a69788796",
        listOf(streaming("csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20", "0d1c2b3a4f5e6d7c8b9a0f1e2d3c4b5a69788796", 182_000_000)),
    ),
)
