package com.chencohensolution.transcriptor.ui

import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.models.SpeechModel

/**
 * The kinds of speech model found on Hugging Face as files the app's engines read: whisper.cpp's for [Whisper], and
 * sherpa-onnx's for the rest. [runs]: the app runs it now; the others need sherpa-onnx's offline recognizer in the
 * app first (its engine reads their files already).
 */
enum class HubKind(val runs: Boolean, val label: Int) {
    Whisper(true, R.string.models_b2_kind_whisper),
    Streaming(true, R.string.models_b2_kind_streaming),
    Parakeet(false, R.string.models_b3_kind_parakeet),
    SenseVoice(false, R.string.models_b3_kind_sensevoice),
    Moonshine(false, R.string.models_b3_kind_moonshine),
    Paraformer(false, R.string.models_b3_kind_paraformer),
}

/** A search result: the repo ([HubModel], b2's) and its kind. */
data class HubEntry(val model: HubModel, val kind: HubKind) {
    /** It transcribes [language]: made for it, or multilingual. */
    fun knows(language: String) = language in model.languages || SpeechModel.MULTILINGUAL in model.languages
}

/** The Hugging Face search with its filters: a language (null: any) and a kind (null: all). */
data class HubSearchB3(
    val query: String = "",
    val language: String? = null,
    val kind: HubKind? = null,
    val searching: Boolean = false,
    val results: List<HubEntry> = emptyList(),
    /** Repos that matched but have no files the app's engines read (PyTorch, safetensors…). */
    val unusable: Int = 0,
)
