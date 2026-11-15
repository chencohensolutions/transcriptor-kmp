package com.chencohensolution.transcriptor.ui

// Fakes: the microphone (FakeStereoLab): both channels' levels and peaks at 10 Hz. Recording only, as hen saw it.
internal val lectureMeterA = meterOption("meter-a", "Two bars, L and R, with a peak", "Each channel's level as a bar; red near the top where it would clip") { StereoBarsA(it.now) }
