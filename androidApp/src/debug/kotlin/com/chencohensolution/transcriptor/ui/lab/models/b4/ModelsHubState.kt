package com.chencohensolution.transcriptor.ui

import com.chencohensolution.transcriptor.models.ModelFile
import com.chencohensolution.transcriptor.models.ModelKind
import com.chencohensolution.transcriptor.models.SpeechModel

/** What a model's license lets the user do with its transcripts, as far as its Hugging Face card says. */
enum class LicenseUse { Commercial, NonCommercial, Unknown }

/**
 * A model repo on Hugging Face with files the app can run: whisper.cpp's `ggml-*.bin` ([ModelKind.Whisper]) or a
 * sherpa-onnx streaming transducer ([ModelKind.StreamingTransducer]). From the Hub's API: the repo's card (license,
 * languages, downloads), its files with their sizes and sha256, and the commit they are pinned to.
 */
data class HubModel(
    val repo: String,
    val kind: ModelKind,
    /** The license's id on the card ("apache-2.0", "cc-by-nc-4.0"); null when the card names none. */
    val license: String?,
    val use: LicenseUse,
    /** BCP-47 tags from the card; [SpeechModel.MULTILINGUAL] for a model of many languages. */
    val languages: List<String>,
    val downloads: Int,
    val likes: Int,
    /** The commit the files are pinned to (the repo's main when it was found). */
    val commit: String,
    /** What can be installed from it: a ggml file each (its size and quantization), or the streaming model's files. */
    val variants: List<HubVariant>,
) {
    val author get() = repo.substringBefore('/')
    val name get() = repo.substringAfter('/')
}

/** One installable form of a [HubModel]: "large-v3-turbo q5_0", with its files. */
data class HubVariant(val label: String, val files: List<ModelFile>, val parameters: String, val fitsLive: Boolean) {
    val sizeBytes get() = files.sumOf { it.sizeBytes }
}

/** The Hugging Face search: what was asked, whether it runs, what came back, and how many repos it left out. */
data class HubSearch(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<HubModel> = emptyList(),
    /** Repos that matched but have no files the app can run (PyTorch, safetensors…). */
    val unusable: Int = 0,
    val error: String? = null,
)

/** The model a [variant] of [hub] installs as: pinned to its commit, checked against the Hub's sha256. */
fun HubModel.toSpeechModel(variant: HubVariant) = SpeechModel(
    id = "hf:$repo:${variant.label}",
    name = "$name · ${variant.label}",
    languages = languages,
    parameters = variant.parameters,
    license = license ?: "?",
    fitsLive = variant.fitsLive,
    source = "https://huggingface.co/$repo/tree/$commit",
    kind = kind,
    files = variant.files,
)

/** Added from Hugging Face (its id says so). */
val SpeechModel.fromHub get() = id.startsWith("hf:")
