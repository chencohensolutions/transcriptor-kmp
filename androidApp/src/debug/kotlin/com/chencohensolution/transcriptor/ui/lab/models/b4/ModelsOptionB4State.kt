package com.chencohensolution.transcriptor.ui

import com.chencohensolution.transcriptor.models.SpeechModel

/**
 * A build for one NPU: compiled for [chip] (Android's `Build.SOC_MODEL`, "SM8750") with Qualcomm's QNN [runtime]
 * ("2.40"). Found on Hugging Face by its files (QNN context binaries, `.bin` beside no `.onnx`) and the chip in the
 * repo's name or tags; whether it loads is known only once it is tried ([NpuCheck]).
 */
data class NpuBuild(val chip: String, val runtime: String)

/** A search result: the repo, its kind, and the NPU it was compiled for (null: it runs on the CPU). */
data class HubEntryB4(val model: HubModel, val kind: HubKind, val npu: NpuBuild? = null) {
    fun knows(language: String) = language in model.languages || SpeechModel.MULTILINGUAL in model.languages

    /** It can run on a phone with [chip]: any phone for a CPU model, that chip's for an NPU build. */
    fun fits(chip: String?) = npu == null || npu.chip == chip
}

/** The Hugging Face search with its filters: a language, a kind, and builds for this phone's NPU only. */
data class HubSearchB4(
    val query: String = "",
    val language: String? = null,
    val kind: HubKind? = null,
    val npuOnly: Boolean = false,
    val searching: Boolean = false,
    val results: List<HubEntryB4> = emptyList(),
    val unusable: Int = 0,
)

/**
 * The one-time check of an NPU model once it is downloaded: loaded on the NPU with a second of silence through it.
 * Qualcomm's runtime refuses a build for another chip or runtime at once, so it takes seconds.
 */
sealed interface NpuCheck {
    data object Checking : NpuCheck
    data object Runs : NpuCheck
    data class Fails(val reason: String) : NpuCheck
}
