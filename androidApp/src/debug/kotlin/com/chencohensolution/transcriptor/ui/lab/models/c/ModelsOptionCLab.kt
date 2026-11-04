package com.chencohensolution.transcriptor.ui

// Fakes: downloads (a timer, then checking), the free space, the languages (the lab notebooks': Hebrew, English),
// choosing a refine model (the app uses the first ready one), the recommendation (the catalog's first live model for
// the language on this chip and its first large one; nothing measures the phone). The chip is the phone's, so the NPU
// models show only on an SM8750. Back from Settings does nothing.
internal val modelsOptionC = LabOption("c", "Settings › Models, set up for you", "A tab per language with the recommended setup and one Download all; Choose yourself for the rest") {
    val (state, actions) = rememberModelsLab()
    ModelsScreenC(state, actions)
}
