package com.chencohensolution.transcriptor.ui

import com.chencohensolution.transcriptor.speech.SherpaNpuWhisperEngine

// Fakes: as option a (the SM8750, ResNet293 copied by hand, Add a language adds French, Manage models does nothing).
internal val modelsOptionC = LabOption("c", "A short Settings", "Languages, a Speakers row, and one Recording and storage row for the rest") {
    ModelsLab(chip = SherpaNpuWhisperEngine.chip ?: "SM8750") { s, a, h -> ModelsScreenC(s, a, h) }
}
