package com.chencohensolution.transcriptor.ui

// Fakes: the recording and live transcription (LabData's lines said again a word at a time, at an uneven pace, so
// lines keep coming). The real model sends a line's words in bursts and may rewrite the last words it sent; this lab
// only ever adds words. Tapping a line does nothing.
internal val lectureOptionC = LabOption("c", "Words fade in", "Each word fades in; a finished line settles from grey into place") {
    val (state, actions) = rememberGrowingLectureLab()
    LectureScreenC(state, actions)
}
