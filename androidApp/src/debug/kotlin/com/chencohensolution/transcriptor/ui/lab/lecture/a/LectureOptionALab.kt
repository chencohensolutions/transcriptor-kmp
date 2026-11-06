package com.chencohensolution.transcriptor.ui

// Fakes: the recording and live transcription (LabData's lines said again a word at a time, so lines keep coming);
// saving a line only changes it in memory (the app would save it to lecture.json and mark it edited, so the improve
// pass keeps it). Stop does nothing. Not shown: a live line split by speaker after the user edited it.
internal val lectureOptionA = LabOption("a", "Fix a line in place", "Tap a line: it becomes a field where it is; live lines keep coming below") {
    val (state, actions, editLine) = rememberGrowingLectureLab()
    LectureScreenA(state, actions, editLine)
}
