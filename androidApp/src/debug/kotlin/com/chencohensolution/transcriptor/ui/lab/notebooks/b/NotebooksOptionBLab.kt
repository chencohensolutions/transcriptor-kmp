package com.chencohensolution.transcriptor.ui

// Fakes: the notebooks and the time (LabData), a refine pass running on the latest lecture, recording (a clock; the
// banner stops it and lists the lecture), creating a notebook in memory. Opening a lecture and Settings only say so.
// The tiles' colors are the speakers' palette by position: notebooks have no color of their own yet.
internal val notebooksOptionB = LabOption("b", "Shelf, then the notebook", "Notebook tiles; a notebook's page has a big Record button over its lectures") {
    val (state, actions) = rememberNotebooksLab()
    NotebooksScreenB(state, actions)
}

internal val notebooksOptionBEmpty = LabOption("b-empty", "Shelf, none yet", "Option b on the first launch") {
    val (state, actions) = rememberNotebooksLab(empty = true)
    NotebooksScreenB(state, actions)
}
