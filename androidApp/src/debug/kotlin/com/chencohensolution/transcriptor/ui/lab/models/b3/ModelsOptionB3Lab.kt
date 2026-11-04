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

// Fakes: everything b2 fakes (downloads, choosing a refine model, the search: no network, a second's wait, then a
// fixed list matched by words), plus the filters on that list and the languages added to the catalog (in memory).
// The repos are real; their downloads, likes, commits and sha256 are made up, and the licenses of the Parakeet,
// SenseVoice, Moonshine and Paraformer repos are as their cards were last seen, not checked. The two lab-example/
// repos are made up whole. Kinds the app doesn't run yet can be looked at, not added.
internal val modelsOptionB3 = LabOption("b3", "Catalog + Hugging Face, any language", "b2 with any language in the catalog, and the search filtered by language and by kind of model (Whisper, streaming, Parakeet…)") {
    var added by remember { mutableStateOf(emptyList<SpeechModel>()) }
    var languages by remember { mutableStateOf(listOf("he", "en")) }
    val (base, actions) = rememberModelsLab(added)
    var hub by remember { mutableStateOf(HubSearchB3()) }
    var asked by remember { mutableStateOf<HubSearchB3?>(null) }
    LaunchedEffect(asked) {
        val q = asked ?: return@LaunchedEffect
        hub = q.copy(searching = true, results = hub.results)
        delay(800)
        val found = hubCatalogB3.filter { e ->
            (q.kind == null || e.kind == q.kind) && (q.language == null || e.knows(q.language)) && matchesB3(e, q.query)
        }
        hub = q.copy(searching = false, results = found, unusable = if (found.isEmpty()) 4 else 23)
        asked = null
    }
    ModelsScreenB3(
        base.copy(added = added, languages = languages), actions, hub,
        HubActionsB3(
            search = { query, language, kind -> asked = HubSearchB3(query.trim(), language, kind) },
            install = { m, v ->
                val model = m.toSpeechModel(v)
                if (added.none { it.id == model.id }) added = added + model
                actions.download(model)
            },
            remove = { model -> actions.delete(model); added = added.filter { it.id != model.id } },
            addLanguage = { if (it !in languages) languages = languages + it },
        ),
    )
}

/** Every word of [query] is in the repo's name or its kind's (an empty query matches all). */
private fun matchesB3(e: HubEntry, query: String): Boolean {
    val text = (e.model.repo + " " + e.kind.name).lowercase()
    return query.lowercase().split(' ').filter { it.isNotBlank() }.all { it in text }
}

private fun ggmlB3(repo: String, commit: String, label: String, file: String, size: Long, parameters: String, live: Boolean) =
    HubVariant(label, listOf(ModelFile("model.bin", "https://huggingface.co/$repo/resolve/$commit/$file", "0".repeat(64), size)), parameters, live)

private fun onnxB3(repo: String, commit: String, label: String, files: List<Pair<String, Long>>, parameters: String, live: Boolean) =
    HubVariant(label, files.map { (f, size) -> ModelFile(f, "https://huggingface.co/$repo/resolve/$commit/$f", "0".repeat(64), size) }, parameters, live)

private fun transducer(encoder: Long) = listOf("encoder.int8.onnx" to encoder, "decoder.int8.onnx" to 1_049_000L, "joiner.int8.onnx" to 771_000L, "tokens.txt" to 5_000L)

private const val CPP_B3 = "ggerganov/whisper.cpp"
private const val CPP_AT_B3 = "5359861c739e955e79d9a303bcbc70fb988958b1"

/** Parakeet TDT 0.6B v3's 25 European languages. */
private val EUROPEAN = listOf(
    "en", "de", "fr", "es", "it", "pt", "nl", "pl", "ru", "uk", "cs", "sk", "sl", "hr", "bg", "ro", "hu", "el", "sv", "da",
    "fi", "et", "lv", "lt", "mt",
)

private fun entry(repo: String, kind: HubKind, license: String?, use: LicenseUse, languages: List<String>, downloads: Int, likes: Int, commit: String, vararg variants: HubVariant) =
    HubEntry(
        HubModel(repo, if (kind == HubKind.Whisper) ModelKind.Whisper else ModelKind.StreamingTransducer, license, use, languages, downloads, likes, commit, variants.toList()),
        kind,
    )

private val hubCatalogB3 = listOf(
    entry(
        CPP_B3, HubKind.Whisper, "mit", LicenseUse.Commercial, listOf(SpeechModel.MULTILINGUAL), 412_000, 4_100, CPP_AT_B3,
        ggmlB3(CPP_B3, CPP_AT_B3, "large-v3-turbo q5_0", "ggml-large-v3-turbo-q5_0.bin", 574_041_195, "809M", true),
        ggmlB3(CPP_B3, CPP_AT_B3, "large-v3-turbo q8_0", "ggml-large-v3-turbo-q8_0.bin", 874_188_075, "809M", true),
        ggmlB3(CPP_B3, CPP_AT_B3, "small q5_1", "ggml-small-q5_1.bin", 190_085_487, "244M", true),
        ggmlB3(CPP_B3, CPP_AT_B3, "large-v3 q5_0", "ggml-large-v3-q5_0.bin", 1_081_140_203, "1.55B", false),
    ),
    entry(
        "ivrit-ai/whisper-large-v3-turbo-ggml", HubKind.Whisper, "apache-2.0", LicenseUse.Commercial, listOf("he"), 9_800, 21,
        "2130c78e4a9cb4914cc4df91a1c3031407789705",
        ggmlB3("ivrit-ai/whisper-large-v3-turbo-ggml", "2130c78e4a9cb4914cc4df91a1c3031407789705", "f16", "ggml-model.bin", 1_624_555_275, "809M", true),
    ),
    entry(
        "lab-example/whisper-small-he-nc", HubKind.Whisper, "cc-by-nc-4.0", LicenseUse.NonCommercial, listOf("he"), 640, 3,
        "1f0e2d3c4b5a69788796a5b4c3d2e1f0a9b8c7d6",
        ggmlB3("lab-example/whisper-small-he-nc", "1f0e2d3c4b5a69788796a5b4c3d2e1f0a9b8c7d6", "q5_1", "ggml-small-he-q5_1.bin", 190_100_000, "244M", true),
    ),
    entry(
        "lab-example/whisper-medium-ar", HubKind.Whisper, null, LicenseUse.Unknown, listOf("ar"), 210, 1,
        "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
        ggmlB3("lab-example/whisper-medium-ar", "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678", "q5_0", "ggml-medium-ar-q5_0.bin", 539_200_000, "769M", true),
    ),
    entry(
        "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26", HubKind.Streaming, "apache-2.0", LicenseUse.Commercial, listOf("en"),
        18_500, 12, "6f3b5d7a9c1e2f4a6b8d0c2e4f6a8b0d2c4e6f8a",
        onnxB3("csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26", "6f3b5d7a9c1e2f4a6b8d0c2e4f6a8b0d2c4e6f8a", "int8", transducer(68_700_000), "70M", true),
    ),
    entry(
        "csukuangfj/sherpa-onnx-streaming-zipformer-fr-2023-04-14", HubKind.Streaming, "apache-2.0", LicenseUse.Commercial, listOf("fr"),
        2_400, 4, "3c5e7a9b1d2f4e6a8c0b2d4f6e8a0c2b4d6f8e0a",
        onnxB3("csukuangfj/sherpa-onnx-streaming-zipformer-fr-2023-04-14", "3c5e7a9b1d2f4e6a8c0b2d4f6e8a0c2b4d6f8e0a", "int8", transducer(70_100_000), "70M", true),
    ),
    entry(
        "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", HubKind.Parakeet, "cc-by-4.0", LicenseUse.Commercial, EUROPEAN,
        31_000, 54, "9b8a7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b",
        onnxB3("csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "9b8a7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b", "int8", transducer(640_000_000), "600M", true),
    ),
    entry(
        "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17", HubKind.SenseVoice, null, LicenseUse.Unknown,
        listOf("zh", "en", "ja", "ko", "yue"), 55_000, 80, "7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a0f9e8d",
        onnxB3("csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17", "7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a0f9e8d", "int8", listOf("model.int8.onnx" to 239_000_000L, "tokens.txt" to 316_000L), "234M", true),
    ),
    entry(
        "csukuangfj/sherpa-onnx-moonshine-base-en-int8", HubKind.Moonshine, "mit", LicenseUse.Commercial, listOf("en"), 8_200, 15,
        "2d4f6a8c0e1b3d5f7a9c2e4b6d8f0a1c3e5b7d9f",
        onnxB3("csukuangfj/sherpa-onnx-moonshine-base-en-int8", "2d4f6a8c0e1b3d5f7a9c2e4b6d8f0a1c3e5b7d9f", "base int8", listOf("preprocess.onnx" to 14_000_000L, "encode.int8.onnx" to 50_000_000L, "uncached_decode.int8.onnx" to 122_000_000L, "cached_decode.int8.onnx" to 100_000_000L, "tokens.txt" to 437_000L), "61M", true),
    ),
    entry(
        "csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09", HubKind.Paraformer, null, LicenseUse.Unknown, listOf("zh", "en"), 12_000, 22,
        "5a7c9e1b3d5f7a9c1e3b5d7f9a1c3e5b7d9f1a3c",
        onnxB3("csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09", "5a7c9e1b3d5f7a9c1e3b5d7f9a1c3e5b7d9f1a3c", "int8", listOf("model.int8.onnx" to 227_000_000L, "tokens.txt" to 76_000L), "220M", true),
    ),
)
