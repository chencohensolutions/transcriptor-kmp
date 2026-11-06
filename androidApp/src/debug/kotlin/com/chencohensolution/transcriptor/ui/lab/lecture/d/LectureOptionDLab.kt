package com.chencohensolution.transcriptor.ui

// Fakes: the recording and live transcription (LabData's lines said again a word at a time, at an uneven pace, so
// lines keep coming). The real model sends a line's words in bursts and may rewrite the last words it sent; this lab
// only ever adds words. Tapping a line does nothing.
internal val lectureOptionD = LabOption("d", "Written out, letter by letter", "The live line is written out evenly with a soft caret; a finished line glows, then fades") {
    val (state, actions) = rememberGrowingLectureLab()
    LectureScreenD(state, actions)
}
