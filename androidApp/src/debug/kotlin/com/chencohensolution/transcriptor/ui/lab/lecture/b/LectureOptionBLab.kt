package com.chencohensolution.transcriptor.ui

// Fakes: the recording and live transcription (LabData's lines said again a word at a time, so lines keep coming);
// saving a line only changes it in memory (the app would save it to lecture.json and mark it edited). Stop does
// nothing. Not shown: a live line split by speaker after the user edited it.
internal val lectureOptionB = LabOption("b", "Fix lines in a sheet", "Tap a line: a sheet with the line, ‹ › to the next ones, and what is said now on top") {
    val (state, actions, editLine) = rememberGrowingLectureLab()
    LectureScreenB(state, actions, editLine)
}
