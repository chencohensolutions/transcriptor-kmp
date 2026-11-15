package com.chencohensolution.transcriptor.models

/**
 * The models a new install starts with: for a language, the one that writes lines live and the one that improves them
 * after, plus the speaker models every language shares. Each job lists its picks best first; the first that runs on the
 * phone's chip is the default, so a Snapdragon 8 Elite gets the NPU build and any other phone the CPU one.
 *
 * Only catalog models. The most promising one per job, whatever its license (the owner's call, Nov 15, 2026): a
 * non-commercial default shows its license in Settings › Models.
 */
object DefaultModels {
    /** The defaults for one language on one phone. A job is null when no pick runs there. */
    data class Defaults(
        val live: SpeechModel?,
        val improve: SpeechModel?,
        val voicePrints: SpeechModel,
        /** Live speaker turns. */
        val segmentation: SpeechModel,
    ) {
        /** The speaker pass's segmentation (pyannote's, the only one it runs): got too, so the pass can run. */
        val speakerPass get() = ModelCatalog.speakerSegmentation

        /** What first run downloads: each model once, smallest first so live works soonest. */
        val toDownload get() = listOfNotNull(live, improve, voicePrints, segmentation, speakerPass).distinctBy { it.id }.sortedBy { it.sizeBytes }
    }

    /** The language a new install starts in: the app ships English first. */
    const val FIRST_LANGUAGE = "en"

    private val livePicks = mapOf(
        "en" to listOf(ModelCatalog.whisperTurbo),
        // The owner's picks on the S25 (Nov 15, 2026).
        "he" to listOf(ModelCatalog.ivritZipformerNpu, ModelCatalog.ivritZipformer),
    )
    private val improvePicks = mapOf(
        "en" to listOf(ModelCatalog.whisperLargeV3),
        "he" to listOf(ModelCatalog.ivritLargeV3Q8),
    )

    /** Live speaker turns: DiariZen on the S25's NPU (the owner's pick, best in the sweep), else pyannote's. */
    private val segmentationPicks = listOf(ModelCatalog.diarizenBaseS80Npu, ModelCatalog.speakerSegmentation)

    /** For a language without picks of its own: the multilingual models. */
    private val multilingualLive = listOf(ModelCatalog.whisperTurbo)
    private val multilingualImprove = listOf(ModelCatalog.whisperLargeV3)

    /**
     * The defaults for [language] on [chip]. [fails] says a model's NPU build was tried here and doesn't load: the next
     * pick (the CPU build) takes its place.
     */
    fun forLanguage(language: String, chip: String?, fails: (SpeechModel) -> Boolean = { false }): Defaults = Defaults(
        live = (livePicks[language] ?: multilingualLive).firstOrNull { it.runsOn(chip) && !fails(it) },
        improve = (improvePicks[language] ?: multilingualImprove).firstOrNull { it.runsOn(chip) && !fails(it) },
        voicePrints = ModelCatalog.voicePrints,
        segmentation = segmentationPicks.first { it.runsOn(chip) && !fails(it) },
    )

    /** Where a language stands against its recommended models ([Defaults.live] and [Defaults.improve]). */
    enum class Setup {
        /** They are the ones that run: nothing to do. */
        Recommended,

        /** Nothing transcribes this language yet: the recommended models are the ones to get. */
        ToGet,

        /** Other models run (the user's own choice, or what was on the phone before): the recommended ones are offered. */
        Custom,
    }

    /**
     * A language's [state], and what "Use recommended" would do: download [toGet] ([bytesToGet] in all; empty when
     * they are on the phone already) and pick the recommended models for both jobs.
     */
    data class LanguageSetup(val state: Setup, val defaults: Defaults, val toGet: List<SpeechModel>) {
        val bytesToGet: Long get() = toGet.sumOf { it.sizeBytes }
    }

    /**
     * How [language] is set up on this phone: [defaults] are its recommended models, [live] and [improve] the ones
     * that run now (null: none), [ready] says a model is downloaded and loads. A job the recommendation has no model
     * for (no pick runs on this chip) doesn't count against it.
     */
    fun setup(defaults: Defaults, live: SpeechModel?, improve: SpeechModel?, ready: (SpeechModel) -> Boolean): LanguageSetup {
        val wanted = listOfNotNull(defaults.live, defaults.improve).distinctBy { it.id }
        val toGet = wanted.filterNot(ready)
        val matches = (defaults.live == null || live?.id == defaults.live.id) && (defaults.improve == null || improve?.id == defaults.improve.id)
        val state = when {
            matches && toGet.isEmpty() -> Setup.Recommended
            live == null && improve == null -> Setup.ToGet
            else -> Setup.Custom
        }
        return LanguageSetup(state, defaults, toGet)
    }

    /**
     * First run is offered while it isn't [done] and nothing that transcribes is downloaded: a phone that already has
     * models (it was set up before first run existed) never sees it.
     */
    fun offerFirstRun(done: Boolean, anyTranscriberReady: Boolean) = !done && !anyTranscriberReady
}
