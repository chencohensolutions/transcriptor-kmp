package com.chencohensolution.transcriptor.ui

// Fakes: playback (a clock; no audio), the refine pass and the speaker pass (progress, then made-up results), the
// lecture's speakers and lines. The overview, seeking, playing a sentence, editing paragraphs, opening notes and the
// speed work in memory.
internal val reviewOptionC = LabOption("c", "Document + overview", "Paragraphs per speaker turn under a lane-per-speaker overview; an edit mode") {
    val (state, actions) = rememberReviewLab()
    ReviewScreenC(state, actions)
}
