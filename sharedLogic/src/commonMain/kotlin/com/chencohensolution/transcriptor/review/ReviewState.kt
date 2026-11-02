package com.chencohensolution.transcriptor.review

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.Segment

/** A pass over the whole recording after the lecture: the refine pass (the large model) or the speaker pass. */
sealed interface PassState {
    data object NotRun : PassState
    data class Running(val progress: Float) : PassState
    data object Done : PassState
}

/** A recorded lecture, opened ([LectureReview]). */
data class ReviewState(
    val notebookTitle: String,
    val lecture: Lecture,
    val positionMillis: Long,
    val playing: Boolean,
    val speed: Float,
    /** The model the refine pass would use; null while the refine pass isn't available. */
    val refineModelName: String?,
    /** Null while the refine pass isn't available. */
    val refine: PassState?,
    /** Null while the speaker pass can't run (its models aren't downloaded). */
    val speakerPass: PassState?,
) {
    /** The line being played (or last played): the one the position is in, else the one before it. */
    val currentLine: Segment? get() = lecture.segments.lastOrNull { it.startMillis <= positionMillis }
}
