package com.chencohensolution.transcriptor.ui

// Fakes: the notebooks and the time (LabData), a refine pass running on the latest lecture, recording (a clock; the
// banner stops it and lists the lecture), creating a notebook in memory. Opening a lecture and Settings only say so.
// The notebooks' colors are the speakers' palette by position: notebooks have no color of their own yet.
internal val notebooksOptionC = LabOption("c", "Lectures first", "Every lecture by date with its notebook, chips to narrow, one Record bar at the bottom") {
    val (state, actions) = rememberNotebooksLab()
    NotebooksScreenC(state, actions)
}

internal val notebooksOptionCEmpty = LabOption("c-empty", "Lectures first, none yet", "Option c on the first launch") {
    val (state, actions) = rememberNotebooksLab(empty = true)
    NotebooksScreenC(state, actions)
}
