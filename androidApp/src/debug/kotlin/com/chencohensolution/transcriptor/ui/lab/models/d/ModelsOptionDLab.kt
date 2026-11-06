package com.chencohensolution.transcriptor.ui

import com.chencohensolution.transcriptor.speech.SherpaNpuWhisperEngine

// Fakes: as option a (the SM8750, ResNet293 copied by hand, Add a language adds French, Manage models does nothing).
internal val modelsOptionD = LabOption("d", "A section per speaker job", "Speaker recognition and Speaker turns as sections on Settings, each a list to pick") {
    ModelsLab(chip = SherpaNpuWhisperEngine.chip ?: "SM8750") { s, a, h -> ModelsScreenD(s, a, h) }
}
