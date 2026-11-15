package com.chencohensolution.transcriptor.ui

// Fakes: the recording and live transcription: LabData's lines said again a word at a time at a speaker's uneven pace,
// so lines keep coming (LectureGrowingLab; the real model sends words in bursts and may rewrite its last ones);
// speaker labels (a lecturer the notebook knew and an unnamed student) and the lag figure. Stop does nothing. Notes,
// pause, fixing a line and renaming a speaker work in memory.
internal val lectureCurrent = LabOption("current", "One timeline, fix a line in place (the app's)", "LectureScreen with a faked recording and live transcription") {
    val (state, actions) = rememberGrowingLectureLab()
    LectureScreen(state, actions)
}

// Fakes: as `current`, recorded with After on the Record bar: no live transcription, so no lines, only the clock, the
// level and the notes.
internal val lectureCurrentRecordOnly = LabOption("current-record-only", "The app's, recording only (After)", "LectureScreen with no live transcription: transcribed after Stop") {
    val (state, actions) = rememberGrowingLectureLab()
    LectureScreen(
        state.copy(
            lecture = state.lecture.copy(segments = emptyList(), speakers = emptyList(), pendingTranscript = true),
            partial = null, liveModelName = null, lagMillis = null, voicePrints = null, speakerPassOnStop = false, speakersLive = false, recordOnly = true,
        ),
        actions,
    )
}
