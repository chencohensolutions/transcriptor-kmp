package com.chencohensolution.transcriptor.ui

import com.chencohensolution.transcriptor.speech.SherpaNpuWhisperEngine

// Fakes: as option a (the SM8750, ResNet293 copied by hand, Add a language adds French, Manage models does nothing).
internal val modelsOptionB = LabOption("b", "Speakers on Settings itself", "The voice model, the slider, the turns and the pass after Stop all on Settings; sheets to change a model") {
    ModelsLab(chip = SherpaNpuWhisperEngine.chip ?: "SM8750") { s, a, h -> ModelsScreenB(s, a, h) }
}
