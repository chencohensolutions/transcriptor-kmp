package com.chencohensolution.transcriptor.models

/**
 * How a model's menu frees it: [Remove] an added model (its files and its entry), [DeleteDownload] a built-in one's
 * files (the catalog keeps it, to download again).
 */
enum class ModelRemoval { Remove, DeleteDownload }

/**
 * What a model is good at when a moment is transcribed again, in one line on the sheet: [Accurate] (a large Whisper:
 * the best text, with punctuation, slower), [Fast] (Whisper turbo: punctuation, quicker), [Streaming] (the Zipformer:
 * quickest, close to the live text, no punctuation).
 */
enum class ModelStrength { Accurate, Fast, Streaming }

/** What a model is chosen for, per language: writing the lines during the lecture, or improving them after. */
enum class ModelJob { Live, Improve }

/**
 * Which models a language offers for each job, in the order Settings and the lecture's Improve sheet list them, and
 * which one runs. [forLanguage] is the language's models on this phone (its own first, then the multilingual ones,
 * as [ModelLibrary.forLanguage] gives them); [ready] says a model is downloaded and loads (its NPU check passed).
 */
object ModelChoices {
    /**
     * A model can do [job]: live, one fast enough to keep up; improve, any model that transcribes. Whisper (CPU or
     * NPU) writes a line at a time with punctuation, listed first; a streaming model (the Zipformer, on the NPU too)
     * is listed after them, for the owner's pick (Nov 16, 2026): writing a moment recorded without a transcript on
     * the NPU, quickly and on little battery, or writing the lines again with it after another live model.
     */
    fun fits(model: SpeechModel, job: ModelJob) = when (job) {
        ModelJob.Live -> model.kind.transcribes && model.runsLive
        ModelJob.Improve -> model.kind.transcribes
    }

    /**
     * The models for [job]: the ready ones first, then those to get; for improving, the large ones before the fast
     * and Whisper before the streaming models.
     * Live, a model that runs in windows ([SpeechModel.liveWindowSeconds]: a large one, slower) is offered only once it
     * is ready (it is got for improving), after the ones that write lines, so it runs live only when chosen.
     */
    fun candidates(forLanguage: List<SpeechModel>, job: ModelJob, ready: (SpeechModel) -> Boolean): List<SpeechModel> {
        val fit = forLanguage.filter { fits(it, job) }
        return when (job) {
            ModelJob.Improve -> fit.sortedWith(compareBy({ it.kind.streams }, { it.fitsLive })).let { it.filter(ready) + it.filterNot(ready) }
            ModelJob.Live -> fit.filter(ready).sortedBy { !it.fitsLive } + fit.filterNot(ready).filter { it.fitsLive }
        }
    }

    /** What [model] is good at for transcribing again ([ModelStrength]). */
    fun strength(model: SpeechModel) = when {
        model.kind.streams -> ModelStrength.Streaming
        model.fitsLive -> ModelStrength.Fast
        else -> ModelStrength.Accurate
    }

    /** The model that runs: the one chosen if it is ready, else the first ready one; null when none is. */
    fun running(candidates: List<SpeechModel>, chosenId: String?, ready: (SpeechModel) -> Boolean): SpeechModel? =
        candidates.firstOrNull { it.id == chosenId && ready(it) } ?: candidates.firstOrNull(ready)

    /**
     * The models to get listed under Improve on a language's page: those not already offered to get under Live, so a
     * model that does both is listed once.
     */
    fun improveToGet(forLanguage: List<SpeechModel>, ready: (SpeechModel) -> Boolean): List<SpeechModel> {
        val live = candidates(forLanguage, ModelJob.Live, ready).filterNot(ready).map { it.id }.toSet()
        return candidates(forLanguage, ModelJob.Improve, ready).filterNot(ready).filter { it.id !in live }
    }

    /** The model to get when a language has nothing to improve with: the first (largest) one that fits. */
    fun improveSuggestion(forLanguage: List<SpeechModel>, ready: (SpeechModel) -> Boolean): SpeechModel? =
        candidates(forLanguage, ModelJob.Improve, ready).takeIf { all -> all.none(ready) }?.firstOrNull()

    /** Whole minutes improving [audioMillis] would take at [speed] (work per audio), at least one; null unknown. */
    fun estimateMinutes(speed: Double?, audioMillis: Long): Int? =
        speed?.let { ((audioMillis * it) / 60_000).toInt().coerceAtLeast(1) }

    /**
     * What [model]'s menu offers ([downloaded]: its files are on the phone): an added model can be removed, a
     * downloaded built-in one its download deleted; a model copied by hand, or not downloaded, neither.
     */
    fun removal(model: SpeechModel, downloaded: Boolean): ModelRemoval? = when {
        model.fromHub -> ModelRemoval.Remove
        model.manual -> null
        downloaded -> ModelRemoval.DeleteDownload
        else -> null
    }
}
