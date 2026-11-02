package com.chencohensolution.transcriptor.ui

// Fakes: the recording (clock, level, the partial line growing), live transcription and the lag figure; Stop and
// tapping a line do nothing. Notes and pause work in memory.
internal val lectureOptionA = LabOption("a", "One timeline", "Lines and notes interleaved; the note bar is always open") {
    val (state, actions) = rememberLiveLectureLab()
    LectureScreenA(state, actions)
}
