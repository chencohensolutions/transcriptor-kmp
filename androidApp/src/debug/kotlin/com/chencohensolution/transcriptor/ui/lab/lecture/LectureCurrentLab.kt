package com.chencohensolution.transcriptor.ui

// Fakes: the recording and live transcription: LabData's lines said again a word at a time at a speaker's uneven pace,
// so lines keep coming (LectureGrowingLab; the real model sends words in bursts and may rewrite its last ones);
// speaker labels (a lecturer the notebook knew and an unnamed student) and the lag figure. Stop does nothing. Notes,
// pause, fixing a line and renaming a speaker work in memory.
internal val lectureCurrent = LabOption("current", "One timeline, fix a line in place (the app's)", "LectureScreen with a faked recording and live transcription") {
    val (state, actions) = rememberGrowingLectureLab()
    LectureScreen(state, actions)
}
