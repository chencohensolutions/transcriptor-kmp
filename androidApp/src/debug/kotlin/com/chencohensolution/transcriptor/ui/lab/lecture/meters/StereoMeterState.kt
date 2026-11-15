package com.chencohensolution.transcriptor.ui

/**
 * Both channels of the recording at one moment, each 0..1 on the meter's dB scale (as the recorder's level is today),
 * with a peak that holds and then falls slowly, so a short loud sound stays visible for a moment.
 */
data class StereoLevel(val left: Float, val right: Float, val leftPeak: Float = left, val rightPeak: Float = right)

/**
 * What the meter options draw, shaped as the recorder would produce it at 10 Hz: [now], the last few seconds of it
 * ([history], oldest first), and for the frequency options the voice range split into [bands] (low to high, 0..1) with
 * their own last few seconds ([bandHistory]). The bands are worked out only while the screen shows them.
 */
data class StereoMeterState(
    val now: StereoLevel,
    val history: List<StereoLevel>,
    val bands: List<Float> = emptyList(),
    val bandHistory: List<List<Float>> = emptyList(),
)

/** How many bands the frequency options split 80 Hz .. 8 kHz into, evenly on a log scale. */
const val METER_BANDS = 24

/** How many readings (at 10 Hz) the strips keep: 8 seconds. */
const val METER_HISTORY = 80
