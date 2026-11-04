package com.chencohensolution.transcriptor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.transcriptor.models.ModelFile
import com.chencohensolution.transcriptor.models.ModelKind
import com.chencohensolution.transcriptor.models.SpeechModel
import com.chencohensolution.transcriptor.speech.ModelStatus
import kotlinx.coroutines.delay

// Fakes: everything b3 fakes, plus the NPU builds in the search (all lab-example/, made up: one for SM8750 with
// QNN 2.40 that loads, one for SM8750 built with QNN 2.31 that the runtime refuses, two for other chips) and the
// one-time NPU check (three seconds once the download is ready, then the made-up outcome; the catalog's NPU models
// pass at once). b4 uses this device's chip (none on the emulator); b4-sm8750 pretends it is a Snapdragon 8 Elite.
internal val modelsOptionB4 = LabOption("b4", "Catalog + Hugging Face + NPU on this phone", "b3 with a filter for builds for this phone's NPU, each marked for this chip or another, checked once downloaded") {
    ModelsLabB4(chip = null)
}

internal val modelsOptionB4Sm8750 = LabOption("b4-sm8750", "b4 on a Snapdragon 8 Elite", "b4 as on an SM8750 phone (the emulator has no NPU)") {
    ModelsLabB4(chip = "SM8750")
}

@Composable
private fun ModelsLabB4(chip: String?) {
    var added by remember { mutableStateOf(emptyList<SpeechModel>()) }
    var languages by remember { mutableStateOf(listOf("he", "en")) }
    val (lab, actions) = rememberModelsLab(added)
    val base = if (chip != null) lab.copy(chip = chip) else lab
    var hub by remember { mutableStateOf(HubSearchB4()) }
    var asked by remember { mutableStateOf<HubSearchB4?>(null) }
    var checks by remember { mutableStateOf(emptyMap<String, NpuCheck>()) }
    LaunchedEffect(asked) {
        val q = asked ?: return@LaunchedEffect
        hub = q.copy(searching = true, results = hub.results)
        delay(800)
        val found = hubCatalogB4.filter { e ->
            (q.kind == null || e.kind == q.kind) && (q.language == null || e.knows(q.language)) &&
                (!q.npuOnly || e.npu?.chip == base.chip) && matchesB4(e, q.query)
        }
        hub = q.copy(searching = false, results = found, unusable = if (found.isEmpty()) 4 else 23)
        asked = null
    }
    // Each NPU model, once downloaded, is tried on the NPU once.
    val ready = (added + com.chencohensolution.transcriptor.models.ModelCatalog.all).filter { it.npu && base.statuses[it.id] == ModelStatus.Ready }.map { it.id }
    LaunchedEffect(ready) {
        for (id in ready.filter { it !in checks }) {
            if (!id.startsWith("hf:")) { checks = checks + (id to NpuCheck.Runs); continue }
            checks = checks + (id to NpuCheck.Checking)
            delay(3_000)
            checks = checks + (id to if ("qnn231" in id) NpuCheck.Fails(OLD_RUNTIME) else NpuCheck.Runs)
        }
    }
    ModelsScreenB4(
        base.copy(added = added, languages = languages), actions, hub,
        HubActionsB4(
            search = { query, language, kind, npuOnly -> asked = HubSearchB4(query.trim(), language, kind, npuOnly) },
            install = { m, v ->
                val npu = hubCatalogB4.firstOrNull { it.model.repo == m.repo }?.npu
                val model = m.toSpeechModel(v).let { if (npu != null) it.copy(chips = listOf(npu.chip)) else it }
                if (added.none { it.id == model.id }) added = added + model
                actions.download(model)
            },
            remove = { model -> actions.delete(model); added = added.filter { it.id != model.id }; checks = checks - model.id },
            addLanguage = { if (it !in languages) languages = languages + it },
        ),
        checks,
    )
}

private const val OLD_RUNTIME = "Built with QNN 2.31; this app runs 2.40, and the NPU refused it."

private fun matchesB4(e: HubEntryB4, query: String): Boolean {
    val text = (e.model.repo + " " + e.kind.name + if (e.npu != null) " npu qnn" else "").lowercase()
    return query.lowercase().split(' ').filter { it.isNotBlank() }.all { it in text }
}

private fun filesB4(repo: String, commit: String, files: List<Pair<String, Long>>) =
    files.map { (f, size) -> ModelFile(f, "https://huggingface.co/$repo/resolve/$commit/$f", "0".repeat(64), size) }

private fun cpu(repo: String, kind: HubKind, modelKind: ModelKind, license: String, languages: List<String>, downloads: Int, commit: String, label: String, files: List<Pair<String, Long>>, parameters: String, live: Boolean) =
    HubEntryB4(HubModel(repo, modelKind, license, LicenseUse.Commercial, languages, downloads, downloads / 1000, commit, listOf(HubVariant(label, filesB4(repo, commit, files), parameters, live))), kind)

private fun npu(repo: String, kind: HubKind, modelKind: ModelKind, languages: List<String>, chip: String, runtime: String, commit: String, files: List<Pair<String, Long>>, parameters: String) =
    HubEntryB4(
        HubModel(repo, modelKind, "apache-2.0", LicenseUse.Commercial, languages, 300, 2, commit, listOf(HubVariant("$chip · QNN $runtime", filesB4(repo, commit, files), parameters, true))),
        kind, NpuBuild(chip, runtime),
    )

private val hubCatalogB4 = listOf(
    cpu(
        "ggerganov/whisper.cpp", HubKind.Whisper, ModelKind.Whisper, "mit", listOf(SpeechModel.MULTILINGUAL), 412_000,
        "5359861c739e955e79d9a303bcbc70fb988958b1", "large-v3-turbo q5_0", listOf("ggml-large-v3-turbo-q5_0.bin" to 574_041_195L), "809M", true,
    ),
    cpu(
        "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26", HubKind.Streaming, ModelKind.StreamingTransducer, "apache-2.0",
        listOf("en"), 18_500, "6f3b5d7a9c1e2f4a6b8d0c2e4f6a8b0d2c4e6f8a", "int8",
        listOf("encoder.int8.onnx" to 68_700_000L, "decoder.int8.onnx" to 1_049_000L, "joiner.int8.onnx" to 771_000L, "tokens.txt" to 5_000L), "70M", true,
    ),
    npu(
        "lab-example/whisper-tiny-en-qnn-sm8750", HubKind.Whisper, ModelKind.WhisperNpu, listOf("en"), "SM8750", "2.40",
        "c0ffee0000000000000000000000000000000001", listOf("encoder.bin" to 31_000_000L, "decoder.bin" to 70_000_000L, "tokens.txt" to 816_730L), "39M",
    ),
    npu(
        "lab-example/whisper-small-he-qnn231-sm8750", HubKind.Whisper, ModelKind.WhisperNpu, listOf("he"), "SM8750", "2.31",
        "c0ffee0000000000000000000000000000000002", listOf("encoder.bin" to 180_000_000L, "decoder.bin" to 210_000_000L, "tokens.txt" to 816_730L), "244M",
    ),
    npu(
        "lab-example/zipformer-en-qnn-sm8650", HubKind.Streaming, ModelKind.StreamingTransducerNpu, listOf("en"), "SM8650", "2.40",
        "c0ffee0000000000000000000000000000000003", listOf("encoder.bin" to 150_000_000L, "decoder.bin" to 2_100_000L, "joiner.bin" to 1_600_000L, "tokens.txt" to 5_000L), "70M",
    ),
    npu(
        "lab-example/whisper-base-qnn-sm8850", HubKind.Whisper, ModelKind.WhisperNpu, listOf(SpeechModel.MULTILINGUAL), "SM8850", "2.40",
        "c0ffee0000000000000000000000000000000004", listOf("encoder.bin" to 60_000_000L, "decoder.bin" to 120_000_000L, "tokens.txt" to 816_730L), "74M",
    ),
)
