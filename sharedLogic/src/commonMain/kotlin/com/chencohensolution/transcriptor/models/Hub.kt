package com.chencohensolution.transcriptor.models

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** What a model's license lets the user do with its transcripts, as far as its Hugging Face card says. */
@kotlinx.serialization.Serializable
enum class LicenseUse { Commercial, NonCommercial, Unknown }

/**
 * The kinds of model found on Hugging Face as files the app's engines read: whisper.cpp's for [Whisper], sherpa-onnx's
 * for the rest. [runs]: the app runs it now; the others need sherpa-onnx's offline recognizer in the app.
 * [VoicePrints] (a speaker-embedding ONNX, exported for sherpa-onnx) and [SpeakerTurns] (a segmentation ONNX it reads,
 * pyannote's kind) are the speaker models: they transcribe nothing ([speakers]).
 */
enum class HubKind(val runs: Boolean) {
    Whisper(true), Streaming(true), Parakeet(false), SenseVoice(false), Moonshine(false), Paraformer(false),
    VoicePrints(true), SpeakerTurns(true),
    ;

    val speakers get() = this == VoicePrints || this == SpeakerTurns

    companion object {
        /** The speech kinds, for the general search's filters. */
        val speech get() = entries.filter { !it.speakers }
    }
}

/**
 * A build for one NPU: compiled for [chip] (Android's `Build.SOC_MODEL`, "SM8750") with Qualcomm's QNN [runtime]
 * ("2.40"; null when the repo doesn't say). Whether it loads is known once it is tried.
 */
data class NpuBuild(val chip: String, val runtime: String?)

/** One installable form of a [HubModel]: "small-q5_1", with its files (pinned to the repo's commit). */
data class HubVariant(
    val label: String,
    val files: List<ModelFile>,
    val parameters: String,
    val fitsLive: Boolean,
    /** Shrunk on the phone as the catalog's full-precision Whisper files are ("q8_0"); null keeps it. */
    val quantizeTo: String? = null,
    /** Its own languages when not the repo's (an English-only ".en" Whisper). */
    val languages: List<String>? = null,
) {
    val sizeBytes get() = files.sumOf { it.sizeBytes }
}

/**
 * A model repo on Hugging Face with files the app's engines read: its card (license, languages), its sizes, the
 * commit they are pinned to, and the NPU it was compiled for, if it was.
 */
data class HubModel(
    val repo: String,
    val kind: HubKind,
    /** The license's id on the card ("apache-2.0", "cc-by-nc-4.0"); null when it names none. */
    val license: String?,
    val use: LicenseUse,
    /** BCP-47 tags; [SpeechModel.MULTILINGUAL] for many; empty when the card doesn't say. */
    val languages: List<String>,
    val downloads: Int,
    val likes: Int,
    val commit: String,
    val variants: List<HubVariant>,
    val npu: NpuBuild? = null,
) {
    val author get() = repo.substringBefore('/')
    val name get() = repo.substringAfter('/')

    /** It transcribes [language]: made for it, or multilingual. */
    fun knows(language: String) = language in languages || SpeechModel.MULTILINGUAL in languages

    /** It can run on a phone with [chip]: any phone for a CPU model, that chip's for an NPU build. */
    fun fits(chip: String?) = npu == null || npu.chip.equals(chip, ignoreCase = true)

    /** The model a [variant] of it installs as. */
    fun toSpeechModel(variant: HubVariant) = SpeechModel(
        id = "hf:$repo:${variant.label}",
        name = "$name · ${variant.label}",
        languages = variant.languages ?: languages.ifEmpty { listOf(SpeechModel.MULTILINGUAL) },
        parameters = variant.parameters,
        license = license ?: "?",
        fitsLive = variant.fitsLive,
        source = "https://huggingface.co/$repo/tree/$commit",
        kind = when (kind) {
            HubKind.VoicePrints -> ModelKind.SpeakerEmbedding
            HubKind.SpeakerTurns -> ModelKind.SpeakerSegmentation
            HubKind.Streaming -> if (npu != null) ModelKind.StreamingTransducerNpu else ModelKind.StreamingTransducer
            else -> if (npu != null) ModelKind.WhisperNpu else ModelKind.Whisper
        },
        files = variant.files,
        quantizeTo = variant.quantizeTo,
        chips = npu?.let { listOf(it.chip.uppercase()) } ?: emptyList(),
    )
}

/** The Hugging Face search, with its filters: a language (null: any), a kind (null: all), NPU builds for this phone. */
data class HubSearch(
    val query: String = "",
    val language: String? = null,
    val kind: HubKind? = null,
    val npuOnly: Boolean = false,
    val searching: Boolean = false,
    val results: List<HubModel> = emptyList(),
    /** Repos that matched but have no files the app's engines read (PyTorch, safetensors, ncnn…). */
    val unusable: Int = 0,
    val error: String? = null,
    /** Every other repo that matched, with why the app can't use it here: listed, not hidden. */
    val others: List<HubOther> = emptyList(),
)

/** A search result the app can't run (or that the filters leave out), and why ([HubClient.whyNot]). */
data class HubOther(val repo: String, val reason: String, val downloads: Int)

/** A search's results: what the app can run ([models]), and every other repo that matched ([others]). */
data class HubResults(val models: List<HubModel>, val unusable: Int, val others: List<HubOther>)

/** Added from Hugging Face (its id says so). */
val SpeechModel.fromHub get() = id.startsWith("hf:")

/**
 * Hugging Face's public API ([fetch] gets a URL's body): a search, then each repo that may fit read whole (its files'
 * sizes and checksums, its card), classified by its files ([classify]) and filtered.
 */
class HubClient(private val fetch: suspend (url: String) -> String) {
    /**
     * Repos for [query] the app's engines can read, in [language] and of [kind] when given, and only builds for
     * [chip]'s NPU when [npuOnly]; with how many matched but can't be read. A blank query looks for whisper.cpp
     * files, sherpa-onnx models and NPU builds.
     */
    suspend fun search(query: String, language: String?, kind: HubKind?, npuOnly: Boolean, chip: String?): HubResults = coroutineScope {
        val terms = query.trim().ifBlank { null }?.let { listOf(it) } ?: defaultTerms(kind)
        val found = terms.flatMap { parseSearch(fetch("$API/models?search=${encode(it)}&limit=$LIMIT&full=true")) }.distinctBy { it.id }
        val readable = found.filter { classify(it.id, it.files, it.tags) != null }
        val details = readable.take(MAX_READ).map { s -> async { s to runCatching { hubModel(s, parseRepo(fetch("$API/models/${s.id}/revision/main?blobs=true"))) }.getOrNull() } }
            .map { it.await() }
        val read = details.mapNotNull { it.second }
        val (models, filtered) = read.partition { m ->
            (kind == null || m.kind == kind) && (language == null || m.knows(language)) && (!npuOnly || (m.npu != null && m.fits(chip)))
        }
        val others = found.filter { classify(it.id, it.files, it.tags) == null }.map { HubOther(it.id, whyNot(it.id, it.files, it.tags), it.downloads) } +
            filtered.map { HubOther(it.repo, FILTERED, it.downloads) } +
            readable.drop(MAX_READ).map { HubOther(it.id, NOT_READ, it.downloads) } +
            details.filter { it.second == null }.map { HubOther(it.first.id, NOT_READ, it.first.downloads) }
        HubResults(models.sortedByDescending { it.likes }, found.size - readable.size, others.sortedByDescending { it.downloads })
    }

    companion object {
        const val API = "https://huggingface.co/api"
        private const val MAX_READ = 30

        /** How many repos a search asks for: enough that a short word ("wespeak") still finds the less popular ones. */
        private const val LIMIT = 200

        const val FILTERED = "Another kind, language or chip than the filters"
        const val NOT_READ = "Not read: refine the search"

        /** Why the app can't run a repo, from its files, name and tags. */
        fun whyNot(id: String, files: List<String>, tags: List<String>): String {
            val top = files.filter { '/' !in it }.map { it.lowercase() }
            val text = (id + " " + tags.joinToString(" ")).lowercase()
            return when {
                "mlx" in text -> "MLX (Apple) format"
                "coreml" in text || top.any { it.endsWith(".mlpackage") || it.endsWith(".mlmodelc") } -> "Core ML (Apple) format"
                top.any { it.endsWith(".gguf") } -> "GGUF format"
                top.any { it.endsWith(".onnx") } -> "ONNX without sherpa-onnx's model information"
                top.any { it.endsWith(".safetensors") || it.endsWith(".bin") || it.endsWith(".pt") || it.endsWith(".ckpt") } -> "PyTorch / safetensors only"
                top.isEmpty() || top.all { it.startsWith(".") || it.endsWith(".md") || it.endsWith(".json") || it.endsWith(".txt") } -> "No model files"
                else -> "A format the app doesn't run"
            }
        }

        /** What a blank query looks for: a speaker kind's repos by their names, else speech models' files and NPU builds. */
        internal fun defaultTerms(kind: HubKind?) = when (kind) {
            HubKind.VoicePrints -> listOf("speaker-embedding", "speaker-recognition", "wespeaker", "3dspeaker")
            HubKind.SpeakerTurns -> listOf("segmentation", "diarization")
            else -> listOf("ggml", "sherpa-onnx", "qnn")
        }
    }
}

/** A search result as the search lists it: its id, commit, counts, tags and file names. */
internal data class HubSummary(val id: String, val sha: String, val downloads: Int, val likes: Int, val tags: List<String>, val files: List<String>)

/** A repo read whole: its commit, its files with their sizes and checksums, its card's license and languages. */
internal data class HubDetail(val sha: String, val files: List<HubFile>, val license: String?, val languages: List<String>, val tags: List<String>)

internal data class HubFile(val name: String, val size: Long, val sha256: String?, val gitSha1: String?)

private val json = Json { ignoreUnknownKeys = true }

internal fun parseSearch(body: String): List<HubSummary> = json.parseToJsonElement(body).jsonArray.mapNotNull { e ->
    val o = e as? JsonObject ?: return@mapNotNull null
    HubSummary(
        id = o.str("id") ?: return@mapNotNull null,
        sha = o.str("sha") ?: "main",
        downloads = (o["downloads"] as? JsonPrimitive)?.intOrNull ?: 0,
        likes = (o["likes"] as? JsonPrimitive)?.intOrNull ?: 0,
        // Its task ("automatic-speech-recognition") counts as a tag.
        tags = o.strings("tags") + listOfNotNull(o.str("pipeline_tag")),
        files = (o["siblings"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("rfilename") } ?: emptyList(),
    )
}

internal fun parseRepo(body: String): HubDetail {
    val o = json.parseToJsonElement(body).jsonObject
    val card = o["cardData"] as? JsonObject
    val cardLanguages = when (val l = card?.get("language")) {
        is JsonArray -> l.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(l.contentOrNull)
        else -> emptyList()
    }
    return HubDetail(
        sha = o.str("sha") ?: "main",
        files = (o["siblings"] as? JsonArray)?.mapNotNull { s ->
            val f = s as? JsonObject ?: return@mapNotNull null
            val lfs = f["lfs"] as? JsonObject
            HubFile(
                name = f.str("rfilename") ?: return@mapNotNull null,
                size = (f["size"] as? JsonPrimitive)?.longOrNull ?: (lfs?.get("size") as? JsonPrimitive)?.longOrNull ?: 0,
                sha256 = lfs?.str("sha256"),
                gitSha1 = f.str("blobId"),
            )
        } ?: emptyList(),
        license = card?.str("license"),
        languages = cardLanguages,
        tags = o.strings("tags"),
    )
}

/** A search result read whole, as a [HubModel]; null when its files don't make one after all. */
internal fun hubModel(s: HubSummary, d: HubDetail): HubModel? {
    val tags = (s.tags + d.tags).distinct()
    val kind = classify(s.id, d.files.map { it.name }, tags) ?: return null
    val npu = npuOf(s.id, d.files.map { it.name }, tags)
    val commit = d.sha
    val byName = d.files.associateBy { it.name }
    fun file(remote: String, local: String = remote): ModelFile {
        val f = byName.getValue(remote)
        return ModelFile(local, "https://huggingface.co/${s.id}/resolve/$commit/$remote", f.sha256 ?: "", f.size, gitSha1 = f.gitSha1.takeIf { f.sha256 == null })
    }
    val names = d.files.map { it.name }.filter { '/' !in it }
    val variants = when {
        npu != null -> {
            val bins = listOfNotNull(
                names.firstOrNull { it.endsWith(".bin") && "encoder" in it }?.let { file(it, "encoder.bin") },
                names.firstOrNull { it.endsWith(".bin") && "decoder" in it }?.let { file(it, "decoder.bin") },
                names.firstOrNull { it.endsWith(".bin") && "joiner" in it }?.let { file(it, "joiner.bin") },
                names.firstOrNull { it.endsWith("tokens.txt") }?.let { file(it, "tokens.txt") },
            )
            listOf(HubVariant("${npu.chip.uppercase()}${npu.runtime?.let { " · QNN $it" } ?: ""}", bins, parametersOf(s.id), fitsLive = true))
        }
        kind == HubKind.Whisper -> names.filter { isGgml(it) }.map { n ->
            val label = n.removePrefix("ggml-").removeSuffix(".bin").let { if (it == "model") "f16" else it }
            val quantized = Regex("""q\d""").containsMatchIn(label)
            // The repo's name says the size when the file's doesn't (ivrit.ai's "ggml-model.bin").
            val sized = "${s.id} $label".lowercase()
            HubVariant(
                label, listOf(file(n, "model.bin")), parametersOf(sized),
                fitsLive = !("large" in sized && "turbo" !in sized),
                quantizeTo = if (quantized) null else "q8_0",
                languages = if (".en" in label) listOf("en") else null,
            )
        }.sortedBy { it.sizeBytes }
        kind == HubKind.Streaming -> {
            fun part(p: String, int8: Boolean) = names.firstOrNull { it.startsWith(p) && it.endsWith(".onnx") && (".int8." in it) == int8 }
            val tokens = names.firstOrNull { it == "tokens.txt" || it.endsWith("tokens.txt") }
            listOf(true, false).mapNotNull { int8 ->
                val parts = listOf("encoder", "decoder", "joiner").map { part(it, int8) ?: return@mapNotNull null }
                HubVariant(
                    if (int8) "int8" else "fp32",
                    parts.zip(listOf("encoder", "decoder", "joiner")).map { (remote, p) -> file(remote, "$p.int8.onnx") } + listOfNotNull(tokens?.let { file(it, "tokens.txt") }),
                    parametersOf(s.id), fitsLive = true,
                )
            }
        }
        // A speaker model per ONNX file (a repo may hold several models, or fp32 and int8), installed as model.onnx.
        // With its NPU build when the repo has one (the app uses it on that chip, the ONNX elsewhere).
        kind.speakers -> {
            val npuFiles = speakerNpuFiles(s.id, names, tags).map { file(it) }
            names.filter { it.endsWith(".onnx") }.map { n ->
                HubVariant(n.removeSuffix(".onnx") + if (npuFiles.isEmpty()) "" else " + NPU", listOf(file(n, "model.onnx")) + npuFiles, parametersOf(s.id), fitsLive = true)
            }.sortedBy { it.sizeBytes }
        }
        // Not run by the app yet: listed with their ONNX files.
        else -> listOf(HubVariant("onnx", names.filter { it.endsWith(".onnx") || it.endsWith("tokens.txt") }.map { file(it) }, parametersOf(s.id), fitsLive = true))
    }.filter { it.files.isNotEmpty() }
    if (variants.isEmpty()) return null
    val license = d.license ?: tags.firstOrNull { it.startsWith("license:") }?.removePrefix("license:")
    return HubModel(
        repo = s.id, kind = kind, license = license, use = licenseUse(license),
        languages = languagesOf(s.id, kind, d.languages + tags), downloads = s.downloads, likes = s.likes,
        commit = commit, variants = variants, npu = npu,
    )
}

/** What a repo is, by its files and name; null when the app's engines read none of it. */
internal fun classify(id: String, files: List<String>, tags: List<String>): HubKind? {
    val name = id.lowercase()
    val top = files.filter { '/' !in it }
    if (npuOf(id, top, tags) != null) return if (top.any { "joiner" in it }) HubKind.Streaming else HubKind.Whisper
    // Other models ship ggml and ONNX files too (Parakeet's GGUF, language models): their names say what they are.
    speakerKind(id, top, tags)?.let { return it }
    if ("parakeet" in name || "nemo" in name) return if (top.any { it.endsWith(".onnx") }) HubKind.Parakeet else null
    val whisper = "whisper" in name || tags.any { "whisper" in it.lowercase() }
    if (top.any(::isGgml)) return if (whisper) HubKind.Whisper else null
    val onnx = top.any { it.endsWith(".onnx") }
    if (!onnx) return null
    return when {
        "sense-voice" in name || "sensevoice" in name -> HubKind.SenseVoice
        "moonshine" in name -> HubKind.Moonshine
        "paraformer" in name -> HubKind.Paraformer
        ("streaming" in name || "online" in name) && listOf("encoder", "decoder", "joiner").all { p -> top.any { it.startsWith(p) && it.endsWith(".onnx") } } &&
            top.any { it.endsWith("tokens.txt") } -> HubKind.Streaming
        else -> null
    }
}

/**
 * A speaker model sherpa-onnx reads: ONNX files in a sherpa-onnx export (its name, its author or its card's
 * `sherpa-onnx` tag says so; other exports lack the metadata sherpa-onnx needs).
 * - **Speaker turns:** named or tagged for segmentation or diarization (pyannote, DiariZen).
 * - **Voice prints:** named or tagged for speaker embedding, recognition or verification (WeSpeaker, 3D-Speaker,
 *   CAM++, ERes2Net, TitaNet, ECAPA).
 */
internal fun speakerKind(id: String, top: List<String>, tags: List<String> = emptyList()): HubKind? {
    val name = id.lowercase()
    val tagged = tags.map { it.lowercase() }
    if (top.none { it.endsWith(".onnx") }) return null
    if ("sherpa" !in name && id.substringBefore('/') !in SHERPA_AUTHORS && "sherpa-onnx" !in tagged) return null
    val text = name + " " + tagged.joinToString(" ")
    return when {
        listOf("segmentation", "diarization", "pyannote", "diarizen").any { it in text } -> HubKind.SpeakerTurns
        listOf("speaker-embedding", "speaker-recognition", "speaker-verification", "wespeaker", "3dspeaker", "campplus", "eres2net", "titanet", "ecapa").any { it in text } ->
            HubKind.VoicePrints
        else -> null
    }
}

/**
 * A speaker model's NPU build beside its ONNX (Transcriptor's: QNN context binaries for one chip, the chip in the
 * repo's name or tags): the voice-print graphs `t150.bin`, `t300.bin`, `t600.bin`, or the segmentation window
 * `window.bin`. Empty without one.
 */
internal fun speakerNpuFiles(id: String, top: List<String>, tags: List<String>): List<String> {
    val text = (id + " " + tags.joinToString(" ")).lowercase()
    if ("qnn" !in text || Regex("""sm\d{4}""").find(text) == null) return emptyList()
    return top.filter { it in listOf("t150.bin", "t300.bin", "t600.bin", "window.bin") }
}

/** Who publishes sherpa-onnx's exports (its author, the project). */
private val SHERPA_AUTHORS = setOf("csukuangfj", "k2-fsa")

/** An NPU build: QNN context binaries (`.bin`, no `.onnx` beside them) with the chip in the repo's name or tags. */
internal fun npuOf(id: String, files: List<String>, tags: List<String>): NpuBuild? {
    val text = (id + " " + tags.joinToString(" ")).lowercase()
    if ("qnn" !in text && "qairt" !in text && "npu" !in text) return null
    if (files.none { it.endsWith(".bin") && "encoder" in it } || files.any { it.endsWith(".onnx") }) return null
    val chip = Regex("""(sm\d{4})""").find(text)?.groupValues?.get(1) ?: return null
    val runtime = Regex("""(?:qnn|qairt)[-_ v]?(\d+)[._](\d+)""").find(text)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
    return NpuBuild(chip.uppercase(), runtime)
}

/** whisper.cpp's model files (not its Core ML encoders or other tools' files). */
private fun isGgml(name: String) = name.startsWith("ggml-") && name.endsWith(".bin") && "silero" !in name && "vad" !in name ||
    name == "ggml-model.bin"

/** Open licenses that allow commercial use; any "nc" license doesn't; none, or "other", is unknown. */
internal fun licenseUse(license: String?): LicenseUse {
    val l = license?.lowercase() ?: return LicenseUse.Unknown
    return when {
        "-nc" in l || "noncommercial" in l || "non-commercial" in l -> LicenseUse.NonCommercial
        l in COMMERCIAL || l.startsWith("bsd") || l.startsWith("gpl") || l.startsWith("lgpl") || l.startsWith("agpl") -> LicenseUse.Commercial
        else -> LicenseUse.Unknown
    }
}

private val COMMERCIAL = setOf("mit", "apache-2.0", "cc-by-4.0", "cc-by-sa-4.0", "cc-by-3.0", "cc0-1.0", "mpl-2.0", "unlicense", "isc", "openrail")

/**
 * A repo's languages: the card's and tags' language codes; many of them, or a Whisper model without any, is
 * multilingual; else the codes in its name ("…-he", "zh-en").
 */
internal fun languagesOf(id: String, kind: HubKind, tags: List<String>): List<String> {
    val known = Languages.all.toSet()
    val fromCard = tags.map { Languages.normalize(it) }.filter { it in known && it.length in 2..3 }.distinct()
    if (fromCard.size >= 10 || "multilingual" in tags) return listOf(SpeechModel.MULTILINGUAL)
    if (fromCard.isNotEmpty()) return fromCard
    val fromName = id.substringAfter('/').lowercase().split('-', '_', '.').filter { it in known && it.length == 2 }.distinct()
    if (fromName.isNotEmpty()) return fromName
    return if (kind == HubKind.Whisper) listOf(SpeechModel.MULTILINGUAL) else emptyList()
}

/** "244M" for a Whisper "small", from the size names in [text]; "?" when it says none. */
private fun parametersOf(text: String): String {
    val t = text.lowercase()
    return when {
        "turbo" in t -> "809M"
        "large" in t -> "1.55B"
        "medium" in t -> "769M"
        "small" in t -> "244M"
        "base" in t -> "74M"
        "tiny" in t -> "39M"
        "66m" in t -> "66M"
        else -> "?"
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

/** [text] for a URL's query: letters, digits and `-_.~` as they are, the rest percent-encoded as UTF-8. */
internal fun encode(text: String): String = buildString {
    for (b in text.encodeToByteArray()) {
        val c = b.toInt().toChar()
        if (c.isLetterOrDigit() && b >= 0 || c in "-_.~") append(c) else append('%').append(((b.toInt() and 0xff) or 0x100).toString(16).substring(1).uppercase())
    }
}
