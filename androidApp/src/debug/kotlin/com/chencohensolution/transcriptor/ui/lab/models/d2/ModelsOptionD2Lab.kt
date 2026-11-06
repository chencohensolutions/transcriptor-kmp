package com.chencohensolution.transcriptor.ui

import com.chencohensolution.transcriptor.speech.SherpaNpuWhisperEngine

// Fakes: as option a (the SM8750, ResNet293 copied by hand, Add a language adds French, Manage models does nothing).
// Find more on Hugging Face: no network; a fixed list of speaker repos per page (D2HubModel), filtered by the field's
// words, and Add only lists the repo on its page as downloading (nothing downloads, it isn't pickable).
internal val modelsOptionD2 = LabOption("d2", "A section per speaker job, picked on its own page", "As d, but each speaker section is one row with the chosen model that opens a page to pick it") {
    ModelsLab(chip = SherpaNpuWhisperEngine.chip ?: "SM8750") { s, a, h -> ModelsScreenD2(s, a, h) }
}
