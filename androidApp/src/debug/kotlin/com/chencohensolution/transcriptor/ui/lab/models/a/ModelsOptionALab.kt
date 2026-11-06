package com.chencohensolution.transcriptor.ui

import com.chencohensolution.transcriptor.speech.SherpaNpuWhisperEngine

// Fakes: as `current` (downloads, languages, the NPU check), plus: the phone is an SM8750 on the emulator too (the
// NPU marks show); ResNet293 is "copied by hand" and on the phone; Add a language adds French at once (no picker);
// Manage models does nothing (it would be today's catalog, without its Use for … menu items).
internal val modelsOptionA = LabOption("a", "Speakers beside the languages", "A Speakers row like a language's; its page picks the voice model, the slider, the turns and the pass after Stop") {
    ModelsLab(chip = SherpaNpuWhisperEngine.chip ?: "SM8750") { s, a, h -> ModelsScreenA(s, a, h) }
}
