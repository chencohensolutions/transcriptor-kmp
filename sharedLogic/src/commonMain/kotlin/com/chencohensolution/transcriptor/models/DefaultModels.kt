package com.chencohensolution.transcriptor.models

/**
 * The models a new install starts with: for a language, the one that writes lines live and the one that improves them
 * after, plus the speaker models every language shares. Each job lists its picks best first; the first that runs on the
 * phone's chip is the default, so a Snapdragon 8 Elite gets the NPU build and any other phone the CPU one.
 *
 * Only catalog models, so every default is free for commercial use.
 */
object DefaultModels {
    /** The defaults for one language on one phone. A job is null when no pick runs there. */
    data class Defaults(
        val live: SpeechModel?,
        val improve: SpeechModel?,
        val voicePrints: SpeechModel,
        val segmentation: SpeechModel,
    ) {
        /** What first run downloads: each model once, smallest first so live works soonest. */
        val toDownload get() = listOfNotNull(live, improve, voicePrints, segmentation).distinctBy { it.id }.sortedBy { it.sizeBytes }
    }

    /** The language a new install starts in: the app ships English first. */
    const val FIRST_LANGUAGE = "en"

    private val livePicks = mapOf(
        // Words as they are spoken, like Hebrew's.
        "en" to listOf(ModelCatalog.englishZipformer),
        // The owner's picks on the S25 (Nov 15, 2026).
        "he" to listOf(ModelCatalog.ivritZipformerNpu, ModelCatalog.ivritZipformer),
    )
    private val improvePicks = mapOf(
        "en" to listOf(ModelCatalog.whisperLargeV3),
        "he" to listOf(ModelCatalog.ivritLargeV3Q8),
    )

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
        // The owner uses DiariZen for speaker turns; it is non-commercial, so new installs get pyannote's.
        segmentation = ModelCatalog.speakerSegmentation,
    )

    /**
     * First run is offered while it isn't [done] and nothing that transcribes is downloaded: a phone that already has
     * models (it was set up before first run existed) never sees it.
     */
    fun offerFirstRun(done: Boolean, anyTranscriberReady: Boolean) = !done && !anyTranscriberReady
}
