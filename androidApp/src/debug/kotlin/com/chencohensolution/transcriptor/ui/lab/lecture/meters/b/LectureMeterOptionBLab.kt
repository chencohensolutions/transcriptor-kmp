package com.chencohensolution.transcriptor.ui

// Fakes: the microphone (FakeStereoLab): the last 8 s of both channels' levels, a reading every 100 ms.
internal val lectureMeterB = meterOption("meter-b", "The last 8 seconds, L above and R below", "A scrolling strip of both channels: speech rhythm, pauses, balance") { StereoStripB(it.history) }
