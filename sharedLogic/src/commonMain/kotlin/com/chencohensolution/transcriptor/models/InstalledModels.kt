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

    /** Forgets a model's NPU result, so it is checked (and offered) again. */
    fun clearNpu(id: String) = read().let { f -> write(f.copy(npu = f.npu - id)) }

    /** The NPU model being loaded now ([NpuLoadGuard]); still set at start, its load got the app killed. */
    fun loading(): String? = read().loading

    fun setLoading(id: String?) = read().let { f -> write(f.copy(loading = id)) }

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
private data class ModelsFileV1(
    val v: Int = 1,
    val models: List<InstalledV1> = emptyList(),
    val npu: Map<String, NpuV1> = emptyMap(),
    /** The NPU model being loaded ([NpuLoadGuard]). */
    val loading: String? = null,
)

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
            liveWindowSeconds = SpeechModel.liveWindowFor(kind, fitsLive),
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

/**
 * Loads NPU models so that one too big for the phone can't crash the app twice: a model whose files and working
 * memory don't fit in [freeBytes] is refused up front, and each load is marked in `models.json` while it runs.
 *
 * - **A load the app didn't survive:** Android ends the app with no error to catch (the low-memory killer). The mark
 *   is then still there at the next start, and [recover] disables that model for this phone.
 * - **Disabled:** an NPU result that failed ([NpuResult]), so the model is left out of what can run
 *   ([ModelLibrary.forLanguage]) and the pick falls back to the next model that works. Nothing retries it on its
 *   own; [reenable] does, when the user asks.
 */
class NpuLoadGuard(private val installed: InstalledModels, private val freeBytes: () -> Long) {
    class Refused(message: String) : Exception(message)

    /** At start: disables the model whose load was running when the app was last killed; its id, or null. */
    fun recover(): String? {
        val id = installed.loading() ?: return null
        installed.setNpu(id, NpuResult(runs = false, reason = KILLED))
        installed.setLoading(null)
        return id
    }

    /** Runs [load] (the model's NPU engine, made and loaded) for [model], or refuses it: see the class. */
    fun <T> load(model: SpeechModel, load: () -> T): T {
        installed.npu(model.id)?.takeIf { !it.runs }?.let { throw Refused(it.reason ?: "Disabled on this phone") }
        val need = needs(model.sizeBytes)
        val free = freeBytes()
        if (need > free) {
            val reason = "Not enough memory on this phone: needs about ${gb(need)} GB, ${gb(free)} GB free"
            installed.setNpu(model.id, NpuResult(runs = false, reason = reason))
            throw Refused(reason)
        }
        installed.setLoading(model.id)
        try {
            return load()
        } finally {
            installed.setLoading(null)
        }
    }

    /** Lets a disabled model be tried again (after closing other apps, or with a smaller build). */
    fun reenable(id: String) = installed.clearNpu(id)

    companion object {
        const val KILLED = "Not enough memory on this phone: the app was closed while loading it"

        /** What loading [sizeBytes] of NPU files takes: the files, a quarter more while the runtime prepares them, and 512 MB. */
        fun needs(sizeBytes: Long) = sizeBytes + sizeBytes / 4 + 512L * 1024 * 1024

        private fun gb(bytes: Long) = ((bytes / 1e8).toLong() / 10.0).toString()
    }
}
