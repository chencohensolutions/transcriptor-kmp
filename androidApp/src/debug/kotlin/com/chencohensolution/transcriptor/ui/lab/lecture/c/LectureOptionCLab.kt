package com.chencohensolution.transcriptor.ui

// Fakes: the recording (clock, level, the partial line growing) and live transcription; Stop and tapping a line do
// nothing. Notes (the sheet), the margin tabs and pause work in memory.
internal val lectureOptionC = LabOption("c", "Document + recorder", "Paragraphs with notes in the margin; a recorder bar with a big Note button") {
    val (state, actions) = rememberLiveLectureLab()
    LectureScreenC(state, actions)
}
