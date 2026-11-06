package com.chencohensolution.transcriptor.ui

// Fakes: as `notebooks current` (the lab notebooks, Monday before class); deleting takes lectures out of memory and
// says so (the app would delete the lecture's folder: its recording, transcript and notes). Not shown: deleting a
// lecture while a pass runs on it, or the one recording now (it isn't in the list until it stops).
internal val notebooksOptionA = LabOption("a", "A menu on each lecture", "⋮ on every lecture: Delete…, which asks first") {
    val (state, actions, delete) = rememberNotebooksDeleteLab()
    NotebooksScreenA(state, actions, delete)
}
