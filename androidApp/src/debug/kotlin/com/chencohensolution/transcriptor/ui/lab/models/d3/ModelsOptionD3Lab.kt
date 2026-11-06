package com.chencohensolution.transcriptor.ui

import com.chencohensolution.transcriptor.speech.SherpaNpuWhisperEngine

// Fakes: as option a (the SM8750, ResNet293 copied by hand, Add a language adds French, Manage models does nothing).
// Find more on Hugging Face: no network; a fixed list of speaker repos per page (D3HubModel), filtered by the field's
// words, and Add only lists the repo on its page as downloading (nothing downloads, it isn't pickable).
// Improve automatically stays one setting for all languages, though each language's page shows it.
internal val modelsOptionD3 = LabOption("d3", "No After a lecture section", "As d2, with Improve automatically on a language's page and Find the speakers again under Speaker turns") {
    ModelsLab(chip = SherpaNpuWhisperEngine.chip ?: "SM8750") { s, a, h -> ModelsScreenD3(s, a, h) }
}
