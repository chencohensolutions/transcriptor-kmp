package com.chencohensolution.transcriptor.ui

// The UI lab's library (UiLabActivity): every screen, each with the app's current version first (`current`) and then
// the suggestions for it, each in its own folder under lab/<screen>/<option>/ (the ui-lab skill). A screen the app
// doesn't have yet has suggestions only.

internal val labScreens: List<LabScreen> = listOf(
    LabScreen("notebooks", "Notebooks", listOf(
        notebooksOptionC2,
        notebooksOptionC2NoGuess,
    )),
    LabScreen("models", "Settings › Models", listOf(
        modelsOptionA,
        modelsOptionB,
        modelsOptionC,
    )),
    LabScreen("lecture", "Lecture, while recording", listOf(
        lectureCurrent,
    )),
    LabScreen("review", "Lecture, after", listOf(
        reviewCurrent,
    )),
)
