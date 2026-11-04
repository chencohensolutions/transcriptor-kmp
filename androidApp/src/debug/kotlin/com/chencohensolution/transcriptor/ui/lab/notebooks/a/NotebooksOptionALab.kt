package com.chencohensolution.transcriptor.ui

// Fakes: the notebooks and the time (LabData), a refine pass running on the latest lecture, recording (a clock; the
// banner stops it and lists the lecture), creating a notebook in memory. Opening a lecture and Settings only say so.
internal val notebooksOptionA = LabOption("a", "Notebooks with their lectures", "One page: each notebook, its latest lectures and a record button; New lecture asks where") {
    val (state, actions) = rememberNotebooksLab()
    NotebooksScreenA(state, actions)
}

internal val notebooksOptionAEmpty = LabOption("a-empty", "Notebooks with their lectures, none yet", "Option a on the first launch") {
    val (state, actions) = rememberNotebooksLab(empty = true)
    NotebooksScreenA(state, actions)
}
