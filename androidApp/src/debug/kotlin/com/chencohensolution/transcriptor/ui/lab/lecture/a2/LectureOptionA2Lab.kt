package com.chencohensolution.transcriptor.ui

// Fakes: the recording and live transcription (LabData's lines said again a word at a time, at an uneven pace, so
// lines keep coming; the real model sends words in bursts and may rewrite its last words, this lab only adds them);
// saving a line only changes it in memory (the app would save it to lecture.json and mark it edited). Stop does
// nothing.
internal val lectureOptionA2 = LabOption("a2", "Fix a line in place, with motion", "a, plus: the field stays above the keyboard; lines glide up; new words fade in quickly") {
    val (state, actions, editLine) = rememberGrowingLectureLab()
    LectureScreenA2(state, actions, editLine)
}
