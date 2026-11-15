package com.chencohensolution.transcriptor.ui

// Fakes: the microphone and the frequency bands over time (FakeStereoLab): a voice's shape, not an FFT of real sound.
internal val lectureMeterD = meterOption("meter-d", "Spectrogram of the last 8 seconds, L and R beside it", "Words, pauses, pitch and 's' sounds over time; both channels' levels") { SpectrogramD(it) }
