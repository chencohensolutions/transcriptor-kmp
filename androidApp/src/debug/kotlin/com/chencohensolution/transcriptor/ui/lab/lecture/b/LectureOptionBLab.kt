package com.chencohensolution.transcriptor.ui

// Fakes: the recording (clock, level, the partial line growing) and live transcription; Stop and tapping a line do
// nothing. Notes, pause, the divider and jumping from a note's time to the transcript work in memory.
internal val lectureOptionB = LabOption("b", "Two panes", "Transcript on top, a notes page below; Enter starts a new note") {
    val (state, actions) = rememberLiveLectureLab()
    LectureScreenB(state, actions)
}
