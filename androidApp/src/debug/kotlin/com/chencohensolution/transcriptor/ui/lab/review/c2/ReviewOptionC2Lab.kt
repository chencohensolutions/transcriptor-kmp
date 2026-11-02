package com.chencohensolution.transcriptor.ui

// Fakes: playback (a clock; no audio), the refine pass and the speaker pass (progress, then made-up results), the
// lecture's speakers and lines. The overview, seeking, playing a sentence, editing paragraphs, opening notes and the
// speed work in memory.
internal val reviewOptionC2 = LabOption("c2", "Document + player below", "c with the overview and the play controls in a bar at the bottom") {
    val (state, actions) = rememberReviewLab()
    ReviewScreenC2(state, actions)
}
