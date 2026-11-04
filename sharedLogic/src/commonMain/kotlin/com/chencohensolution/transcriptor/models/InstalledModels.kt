package com.chencohensolution.transcriptor.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/** A model added from Hugging Face: what it installs as (pinned, checked), where from, and what its license allows. */
data class InstalledModel(val model: SpeechModel, val repo: String, val use: LicenseUse)

/** How a model's one-time NPU check went: it ran, or the runtime refused it ([reason]). */
data class NpuResult(val runs: Boolean, val reason: String? = null)

/**
 * The models the user added from Hugging Face, and how each NPU model's check went: `models.json` (version 1) under
 * [root], the app's own storage (models stay with the app, not in the lectures folder), written to a temp file and
 * then renamed.
 */
class InstalledModels(private val fileSystem: FileSystem, private val root: Path) {
    private val file get() = root / "models.json"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    fun all(): List<InstalledModel> = read().models.map { it.toInstalled() }

    fun add(model: InstalledModel) = read().let { f -> write(f.copy(models = f.models.filter { it.id != model.model.id } + InstalledV1.of(model))) }

    fun remove(id: String) = read().let { f -> write(f.copy(models = f.models.filter { it.id != id }, npu = f.npu - id)) }

    fun npu(id: String): NpuResult? = read().npu[id]?.let { NpuResult(it.runs, it.reason) }

    fun setNpu(id: String, result: NpuResult) = read().let { f -> write(f.copy(npu = f.npu + (id to NpuV1(result.runs, result.reason)))) }

    private fun read(): ModelsFileV1 =
        if (fileSystem.exists(file)) json.decodeFromString(fileSystem.read(file) { readUtf8() }) else ModelsFileV1()

    private fun write(models: ModelsFileV1) {
        fileSystem.createDirectories(root)
        val temp = root / "models.json.tmp"
        fileSystem.write(temp) { writeUtf8(json.encodeToString(models)) }
        fileSystem.atomicMove(temp, file)
    }
}

/**
 * The models the app can use: the catalog's and those added from Hugging Face ([installed]). The NPU models that
 * failed their check are left out of what can run.
 */
class ModelLibrary(private val installed: InstalledModels) {
    val all: List<SpeechModel> get() = ModelCatalog.all + installed.all().map { it.model }

    /** The models that know [language] and run on [chip]: the ones made for it first, then the multilingual ones. */
    fun forLanguage(language: String, chip: String?): List<SpeechModel> =
        all.filter { it.runsOn(chip) && installed.npu(it.id)?.runs != false }.let { models ->
            models.filter { language in it.languages } + models.filter { SpeechModel.MULTILINGUAL in it.languages && language !in it.languages }
        }

    /** A model's name by its id (a line's or a version's model), or the id when it is no longer here. */
    fun nameOf(id: String): String = all.firstOrNull { it.id == id }?.name ?: ModelCatalog.nameOf(id)

    fun installed(id: String): InstalledModel? = installed.all().firstOrNull { it.model.id == id }

    fun npu(id: String): NpuResult? = installed.npu(id)
}

@Serializable
private data class ModelsFileV1(val v: Int = 1, val models: List<InstalledV1> = emptyList(), val npu: Map<String, NpuV1> = emptyMap())

@Serializable
private data class NpuV1(val runs: Boolean, val reason: String? = null)

@Serializable
private data class FileV1(val name: String, val url: String, val sha256: String, val size: Long, val gitSha1: String? = null)

@Serializable
private data class InstalledV1(
    val id: String, val name: String, val languages: List<String>, val parameters: String, val license: String,
    val fitsLive: Boolean, val source: String, val kind: ModelKind, val files: List<FileV1>, val quantizeTo: String? = null,
    val chips: List<String> = emptyList(), val repo: String, val use: LicenseUse,
) {
    fun toInstalled() = InstalledModel(
        SpeechModel(
            id, name, languages, parameters, license, fitsLive, source, kind,
            files.map { ModelFile(it.name, it.url, it.sha256, it.size, it.gitSha1) }, quantizeTo = quantizeTo, chips = chips,
        ),
        repo, use,
    )

    companion object {
        fun of(i: InstalledModel) = with(i.model) {
            InstalledV1(
                id, name, languages, parameters, license, fitsLive, source, kind,
                files.map { FileV1(it.name, it.url, it.sha256, it.sizeBytes, it.gitSha1) }, quantizeTo, chips, i.repo, i.use,
            )
        }
    }
}
