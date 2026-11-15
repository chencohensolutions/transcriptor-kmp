package com.chencohensolution.transcriptor.ui

// Fakes: the microphone and the frequency bands (FakeStereoLab): a voice's shape, not an FFT of real sound. The app
// would need a small FFT on the recorder's thread, 10 times a second, only while this screen is on.
internal val lectureMeterC = meterOption("meter-c", "Voice spectrum now, L and R lines under it", "24 bands, 80 Hz to 8 kHz, and both channels' levels as thin lines") { SpectrumC(it) }
