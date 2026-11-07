package com.chencohensolution.transcriptor.speech

import android.os.SystemClock
import com.chencohensolution.transcriptor.recording.NpuBusyClock

/**
 * The time this app's models have kept the NPU busy (every call into a QNN model: Whisper, streaming, voice prints,
 * speaker turns), for the lecture screen's NPU meter. The phone doesn't let apps read the NPU's whole load.
 */
val npuBusy = NpuBusyClock { SystemClock.elapsedRealtime() }
