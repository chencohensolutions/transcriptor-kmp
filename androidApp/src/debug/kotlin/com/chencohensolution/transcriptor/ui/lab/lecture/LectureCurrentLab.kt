package com.chencohensolution.transcriptor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// Fakes: the recording and live transcription: LabData's lines said again a word at a time at a speaker's uneven pace,
// so lines keep coming (LectureGrowingLab; the real model sends words in bursts and may rewrite its last ones);
// speaker labels (a lecturer the notebook knew and an unnamed student) and the lag figure. Stop does nothing. Notes,
// pause, fixing a line and renaming a speaker work in memory.
internal val lectureCurrent = LabOption("current", "One timeline, fix a line in place (the app's)", "LectureScreen with a faked recording and live transcription") {
    val (state, actions) = rememberGrowingLectureLab()
    LectureScreen(state, actions, startInTranscript = true)
}

// Fakes: as `current`, as the screen opens: at a glance, with Mark (a note without words, in memory). The location
// switches (Stop when I leave, Stop when back here, Path) turn on and off in memory, with the status line they give;
// no location is read.
internal val lectureCurrentGlance = LabOption("current-glance", "The app's, at a glance (as it opens)", "LectureScreen's glance view over the faked live recording: clock, who is talking, Mark, location switches") {
    val (state, actions) = rememberGrowingLectureLab()
    var leave by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var back by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var path by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val place = if (!leave && !back && !path) null else com.chencohensolution.transcriptor.location.MomentLocation.Status(
        watch = if (leave || back) com.chencohensolution.transcriptor.location.PlaceWatch.State.Watching(null) else com.chencohensolution.transcriptor.location.PlaceWatch.State.Off,
        leaveMeters = 250.takeIf { leave }, points = if (path) 12 else null,
        request = com.chencohensolution.transcriptor.location.MomentLocation.Request.Minute, lastFixAt = 0,
        leaving = leave, returning = back, pathOn = path,
    )
    LectureScreen(
        state, actions, place = place,
        placeActions = PlaceActions(set = { which, on ->
            when (which) { PlaceSwitch.Leave -> leave = on; PlaceSwitch.Back -> back = on; PlaceSwitch.Path -> path = on }
        }),
    )
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
