package com.chencohensolution.transcriptor.review

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.Segment

/** A pass over the whole recording after the lecture: the refine pass (the large model) or the speaker pass. */
sealed interface PassState {
    data object NotRun : PassState
    data class Running(val progress: Float) : PassState
    data object Done : PassState
}

/**
 * A model to pick, by [id], shown as [name]; for improving, about how many [minutes] it would take on this lecture
 * (null until it has run once on this phone) and whether it runs on the [npu].
 */
data class ModelChoice(val id: String, val name: String, val minutes: Int? = null, val npu: Boolean = false)

/** A model to download, by [id], shown as [name] with its size. */
data class ModelToGet(val id: String, val name: String, val sizeBytes: Long)

/** A recorded lecture, opened ([LectureReview]). */
data class ReviewState(
    val notebookTitle: String,
    val lecture: Lecture,
    val positionMillis: Long,
    val playing: Boolean,
    val speed: Float,
    /**
     * The models the lecture can be improved with (downloaded, for its language), the large ones first; empty: none.
     * Offered whether or not lectures are improved on their own.
     */
    val refineModels: List<ModelChoice>,
    /** The improve pass: not run, running or done (it is always offered). */
    val refine: PassState,
    /** The improve model chosen in Settings for the lecture's language: picked to start with. */
    val improveDefault: String? = null,
    /** With no model to improve with: the one to download. */
    val improveToGet: ModelToGet? = null,
    /** Null while the speaker pass can't run (its models aren't downloaded). */
    val speakerPass: PassState?,
) {
    /** The line being played (or last played): the one the position is in, else the one before it. */
    val currentLine: Segment? get() = lecture.segments.lastOrNull { it.startMillis <= positionMillis }
}
