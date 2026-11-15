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
        "en" to listOf(ModelCatalog.whisperTurbo),
        "he" to listOf(ModelCatalog.ivritTurboNpu, ModelCatalog.ivritTurbo),
    )
    private val improvePicks = mapOf(
        "en" to listOf(ModelCatalog.whisperLargeV3),
        "he" to listOf(ModelCatalog.ivritLargeV3Npu, ModelCatalog.ivritLargeV3),
    )

    /** For a language without picks of its own: the multilingual models. */
    private val multilingualLive = listOf(ModelCatalog.whisperTurbo)
    private val multilingualImprove = listOf(ModelCatalog.whisperLargeV3)

    fun forLanguage(language: String, chip: String?): Defaults = Defaults(
        live = (livePicks[language] ?: multilingualLive).firstOrNull { it.runsOn(chip) },
        improve = (improvePicks[language] ?: multilingualImprove).firstOrNull { it.runsOn(chip) },
        voicePrints = ModelCatalog.voicePrints,
        segmentation = ModelCatalog.speakerSegmentation,
    )
}
