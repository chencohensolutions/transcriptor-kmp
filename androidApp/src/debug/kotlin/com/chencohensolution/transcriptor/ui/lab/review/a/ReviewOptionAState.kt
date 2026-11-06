package com.chencohensolution.transcriptor.ui

/**
 * A model the lecture can be improved with: downloaded, for its language. [minutes] is a rough estimate for this
 * lecture, from the model's measured speed on this phone; null when it hasn't run here yet.
 */
data class ImproveChoiceA(val id: String, val name: String, val minutes: Int?, val npu: Boolean)

/** What "Improve transcription…" offers for a lecture, whatever the automatic setting is. */
data class ImproveOptionsA(
    /** The downloaded models for the lecture's language, the most accurate first; empty: none downloaded. */
    val choices: List<ImproveChoiceA>,
    /** The one chosen in Settings for this language (preselected); picking another here doesn't change it. */
    val defaultId: String?,
    /** A model to download when [choices] is empty: its name and size ("ivrit.ai Whisper large-v3", "3.1 GB"). */
    val suggested: Pair<String, String>?,
    /** The speaker pass hasn't run on this lecture (improving drops the word times it needs). */
    val offerSpeakersFirst: Boolean,
)

class ImproveActionsA(
    /** Improves the lecture with a model, after the speaker pass when [speakersFirst]. */
    val improve: (modelId: String, speakersFirst: Boolean) -> Unit = { _, _ -> },
    /** Downloads [ImproveOptionsA.suggested]. */
    val getModel: () -> Unit = {},
)
