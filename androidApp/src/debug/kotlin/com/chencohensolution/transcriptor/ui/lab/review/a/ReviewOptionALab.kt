package com.chencohensolution.transcriptor.ui

// Fakes: playback (a clock; no audio), the refine pass and the speaker pass (progress, then made-up results), the
// lecture's speakers and lines. Seeking, playing a line, editing a line or its speaker, notes and the speed work in
// memory.
internal val reviewOptionA = LabOption("a", "Player + timeline", "The recording screen's timeline with a speaker-colored scrubber; hold a line to edit") {
    val (state, actions) = rememberReviewLab()
    ReviewScreenA(state, actions)
}
