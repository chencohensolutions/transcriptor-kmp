package com.chencohensolution.transcriptor.ui

// Fakes: downloads (a timer, then checking), the free space, the languages (the lab notebooks': Hebrew, English),
// choosing a refine model (the app uses the first ready one). The chip is the phone's, so the NPU models show only on
// an SM8750. Back does nothing.
internal val modelsOptionA = LabOption("a", "One page, a row per job", "Settings on one page; per language a Live and a Refine row naming its model, a sheet to change it") {
    val (state, actions) = rememberModelsLab()
    ModelsScreenA(state, actions)
}
