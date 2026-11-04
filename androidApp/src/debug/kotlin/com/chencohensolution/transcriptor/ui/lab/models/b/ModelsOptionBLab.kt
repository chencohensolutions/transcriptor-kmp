package com.chencohensolution.transcriptor.ui

// Fakes: downloads (a timer, then checking), the free space, the languages (the lab notebooks': Hebrew, English),
// choosing a refine model (the app uses the first ready one). The chip is the phone's, so the NPU models show only on
// an SM8750. Back from Settings does nothing; a model's page opens in the browser.
internal val modelsOptionB = LabOption("b", "Settings › Models, a catalog", "Settings with a Models row; the models as cards per language with Live/Refine badges and a menu") {
    val (state, actions) = rememberModelsLab()
    ModelsScreenB(state, actions)
}
