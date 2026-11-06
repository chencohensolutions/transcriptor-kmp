package com.chencohensolution.transcriptor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.transcriptor.models.HubKind
import com.chencohensolution.transcriptor.models.HubModel
import com.chencohensolution.transcriptor.models.HubSearch
import com.chencohensolution.transcriptor.models.HubVariant
import com.chencohensolution.transcriptor.models.LicenseUse
import com.chencohensolution.transcriptor.models.ModelCatalog
import com.chencohensolution.transcriptor.models.ModelFile
import com.chencohensolution.transcriptor.models.NpuBuild
import com.chencohensolution.transcriptor.models.SpeechModel
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.speech.ModelStatus
import com.chencohensolution.transcriptor.speech.SherpaNpuWhisperEngine
import kotlinx.coroutines.delay

// Fakes: downloads (a model's size at 60 MB/s, then two seconds of checking), the languages (Hebrew, English, and
// those added), the Hugging Face search (no network: a second's wait, then a fixed list filtered as the app filters;
// the repos are real but their likes, commits and sha256 are made up, lab-example/ repos are made up whole), and the NPU
// check (three seconds, then a made-up outcome; the catalog's NPU models pass at once). To start with: Hebrew's turbo
// and the speaker models are ready, Whisper turbo waits for Wi-Fi and Whisper large-v3 failed. `current` uses this
// device's chip (none on the emulator); `current-sm8750` pretends it is a Snapdragon 8 Elite.
internal val modelsCurrent = LabOption("current", "A page per language + the catalog (the app's)", "ModelsScreen with faked downloads, search and NPU checks") {
    ModelsLab(chip = SherpaNpuWhisperEngine.chip)
}

internal val modelsCurrentSm8750 = LabOption("current-sm8750", "The app's, on a Snapdragon 8 Elite", "ModelsScreen as on an SM8750 phone (the emulator has no NPU)") {
    ModelsLab(chip = "SM8750")
}

@Composable
private fun ModelsLab(chip: String?) {
    var statuses by remember {
        mutableStateOf(
            mapOf(
                ModelCatalog.ivritTurbo.id to ModelStatus.Ready,
                ModelCatalog.voicePrints.id to ModelStatus.Ready,
                ModelCatalog.speakerSegmentation.id to ModelStatus.Ready,
                ModelCatalog.whisperTurbo.id to ModelStatus.WaitingForWifi,
                ModelCatalog.whisperLargeV3.id to ModelStatus.Failed("download error 1008"),
            ),
        )
    }
    var added by remember { mutableStateOf(emptyList<SpeechModel>()) }
    var languages by remember { mutableStateOf(listOf("he", "en")) }
    var live by remember { mutableStateOf(emptyMap<String, String>()) }
    var refine by remember { mutableStateOf(emptyMap<String, String>()) }
    var format by remember { mutableStateOf(AudioFormat.WavThenFlac) }
    var flacLevel by remember { mutableStateOf(5) }
    var speakers by remember { mutableStateOf(true) }
    var auto by remember { mutableStateOf(false) }
    var hub by remember { mutableStateOf(HubSearch()) }
    var asked by remember { mutableStateOf<HubSearch?>(null) }
    var npu by remember { mutableStateOf(emptyMap<String, NpuCheck>()) }
    val models = ModelCatalog.all + added
    val known = models + speakerModels
    LaunchedEffect(statuses.values.any { it is ModelStatus.Downloading || it == ModelStatus.Checking }) {
        val ticks = mutableMapOf<String, Int>()
        while (statuses.values.any { it is ModelStatus.Downloading || it == ModelStatus.Checking }) {
            delay(200)
            statuses = statuses.mapValues { (id, s) ->
                val model = known.firstOrNull { it.id == id } ?: return@mapValues s
                when (s) {
                    is ModelStatus.Downloading -> (s.progress + 12_000_000f / model.sizeBytes).let { if (it >= 1f) ModelStatus.Checking else ModelStatus.Downloading(it) }
                    ModelStatus.Checking -> { ticks[id] = (ticks[id] ?: 0) + 1; if (ticks.getValue(id) >= 10) ModelStatus.Ready else s }
                    else -> s
                }
            }
        }
    }
    val readyNpu = models.filter { (it.kind.name.endsWith("Npu")) && it.runsOn(chip) && statuses[it.id] == ModelStatus.Ready }.map { it.id }
    LaunchedEffect(readyNpu) {
        for (id in readyNpu.filter { it !in npu }) {
            if (!id.startsWith("hf:")) { npu = npu + (id to NpuCheck.Runs); continue }
            npu = npu + (id to NpuCheck.Checking)
            delay(3_000)
            npu = npu + (id to if ("qnn231" in id) NpuCheck.Fails("Built with QNN 2.31; this app runs 2.40, and the NPU refused it.") else NpuCheck.Runs)
        }
    }
    LaunchedEffect(asked) {
        val q = asked ?: return@LaunchedEffect
        hub = q.copy(searching = true, results = hub.results)
        delay(800)
        val found = labHub.filter { m ->
            (q.kind == null || m.kind == q.kind) && q.language.let { l -> l == null || m.knows(l) } && (!q.npuOnly || (m.npu != null && m.fits(chip))) &&
                q.query.lowercase().split(' ').filter { it.isNotBlank() }.all { it in (m.repo + " " + m.kind.name).lowercase() }
        }
        hub = q.copy(searching = false, results = found, unusable = if (found.isEmpty()) 4 else 23)
        asked = null
    }
    val download = { m: SpeechModel -> statuses = statuses + (m.id to ModelStatus.Downloading(0f)) }
    ModelsScreen(
        ModelsState(
            languages = languages, chip = chip, models = models, statuses = statuses, live = live, refine = refine, npu = npu,
            audioFormat = format, flacLevel = flacLevel, findSpeakersOnStop = speakers, autoImprove = auto,
        ),
        ModelsActions(
            download = download,
            cancel = { statuses = statuses - it.id },
            delete = { statuses = statuses - it.id; npu = npu - it.id },
            useLive = { l, m -> live = live + (l to m.id) },
            useRefine = { l, m -> refine = refine + (l to m.id) },
            setAudioFormat = { format = it },
            setFlacLevel = { flacLevel = it },
            setFindSpeakersOnStop = { speakers = it },
            setAutoImprove = { auto = it },
            addLanguage = { if (it !in languages) languages = languages + it },
            search = { query, language, kind, npuOnly -> asked = HubSearch(query.trim(), language, kind, npuOnly) },
            install = { m, v -> val model = m.toSpeechModel(v); if (added.none { it.id == model.id }) added = added + model; download(model) },
            remove = { m -> statuses = statuses - m.id; added = added.filter { it.id != m.id }; npu = npu - m.id },
        ),
        hub,
    )
}

private fun files(repo: String, commit: String, vararg names: Pair<String, Long>) =
    names.map { (f, size) -> ModelFile(f, "https://huggingface.co/$repo/resolve/$commit/$f", "0".repeat(64), size) }

private fun ggml(repo: String, commit: String, label: String, size: Long, parameters: String, live: Boolean) =
    HubVariant(label, files(repo, commit, "model.bin" to size), parameters, live)

private val labHub = listOf(
    HubModel(
        "ggerganov/whisper.cpp", HubKind.Whisper, "mit", LicenseUse.Commercial, listOf(SpeechModel.MULTILINGUAL), 0, 1_678, "5359861c739e955e79d9a303bcbc70fb988958b1",
        listOf(
            ggml("ggerganov/whisper.cpp", "5359861c", "small-q5_1", 190_085_487, "244M", true),
            ggml("ggerganov/whisper.cpp", "5359861c", "large-v3-turbo-q5_0", 574_041_195, "809M", true),
            ggml("ggerganov/whisper.cpp", "5359861c", "large-v3", 3_095_033_483, "1.55B", false),
        ),
    ),
    HubModel(
        "lab-example/whisper-small-he-nc", HubKind.Whisper, "cc-by-nc-4.0", LicenseUse.NonCommercial, listOf("he"), 640, 3, "1f0e2d3c",
        listOf(ggml("lab-example/whisper-small-he-nc", "1f0e2d3c", "q5_1", 190_100_000, "244M", true)),
    ),
    HubModel(
        "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21", HubKind.Streaming, "apache-2.0", LicenseUse.Commercial, listOf("en"), 0, 12, "6f3b5d7a",
        listOf(HubVariant("int8", files("csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21", "6f3b5d7a", "encoder.int8.onnx" to 68_700_000L, "decoder.int8.onnx" to 1_049_000L, "joiner.int8.onnx" to 771_000L, "tokens.txt" to 5_000L), "70M", true)),
    ),
    HubModel(
        "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", HubKind.Parakeet, "cc-by-4.0", LicenseUse.Commercial, listOf("en", "de", "fr", "es", "it"), 0, 54, "9b8a7c6d",
        listOf(HubVariant("onnx", files("csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "9b8a7c6d", "encoder.int8.onnx" to 640_000_000L), "600M", true)),
    ),
    HubModel(
        "lab-example/whisper-tiny-en-qnn-sm8750", HubKind.Whisper, "apache-2.0", LicenseUse.Commercial, listOf("en"), 300, 2, "c0ffee01",
        listOf(HubVariant("SM8750 · QNN 2.40", files("lab-example/whisper-tiny-en-qnn-sm8750", "c0ffee01", "encoder.bin" to 31_000_000L, "decoder.bin" to 70_000_000L, "tokens.txt" to 816_730L), "39M", true)),
        NpuBuild("SM8750", "2.40"),
    ),
    HubModel(
        "lab-example/whisper-small-he-qnn231-sm8750", HubKind.Whisper, "apache-2.0", LicenseUse.Commercial, listOf("he"), 300, 2, "c0ffee02",
        listOf(HubVariant("SM8750 · QNN 2.31", files("lab-example/whisper-small-he-qnn231-sm8750", "c0ffee02", "encoder.bin" to 180_000_000L, "decoder.bin" to 210_000_000L, "tokens.txt" to 816_730L), "244M", true)),
        NpuBuild("SM8750", "2.31"),
    ),
    HubModel(
        "lab-example/zipformer-en-qnn-sm8650", HubKind.Streaming, "apache-2.0", LicenseUse.Commercial, listOf("en"), 300, 2, "c0ffee03",
        listOf(HubVariant("SM8650 · QNN 2.40", files("lab-example/zipformer-en-qnn-sm8650", "c0ffee03", "encoder.bin" to 150_000_000L, "decoder.bin" to 2_100_000L, "joiner.bin" to 1_600_000L, "tokens.txt" to 5_000L), "70M", true)),
        NpuBuild("SM8650", "2.40"),
    ),
)
