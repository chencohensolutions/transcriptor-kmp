package com.chencohensolution.transcriptor.ui

// The UI lab's library (UiLabActivity): every screen, each with the app's current version first (`current`) and then
// the suggestions for it, each in its own folder under lab/<screen>/<option>/ (the ui-lab skill). A screen the app
// doesn't have yet has suggestions only.

internal val labScreens: List<LabScreen> = listOf(
    LabScreen("notebooks", "Notebooks", listOf(
        notebooksCurrent,
        notebooksCurrentNoGuess,
        notebooksOptionB,
    )),
    LabScreen("models", "Settings", listOf(
        modelsCurrent,
        modelsCurrentSm8750,
    )),
    LabScreen("lecture", "Lecture, while recording", listOf(
        lectureCurrent,
        lectureOptionA2,
    )),
    LabScreen("review", "Lecture, after", listOf(
        reviewCurrent,
        reviewCurrentNoModel,
        reviewOptionB,
    )),
)
