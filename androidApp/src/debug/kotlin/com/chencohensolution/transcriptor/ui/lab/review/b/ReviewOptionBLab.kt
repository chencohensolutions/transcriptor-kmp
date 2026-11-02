package com.chencohensolution.transcriptor.ui

// Fakes: playback (a clock; no audio), the refine pass and the speaker pass (progress, then made-up results), the
// lecture's speakers and lines. Tabs, seeking, playing a line or a speaker's sample, editing lines, notes (add, edit,
// delete), renaming and the speed work in memory.
internal val reviewOptionB = LabOption("b", "Tabs", "Transcript, Notes and Speakers tabs over a mini player; edit a line in place") {
    val (state, actions) = rememberReviewLab()
    ReviewScreenB(state, actions)
}
