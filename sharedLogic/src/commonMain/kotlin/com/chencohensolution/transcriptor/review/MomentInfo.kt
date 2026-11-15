package com.chencohensolution.transcriptor.review

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.recording.PowerReport

/**
 * A moment's details for its Info sheet: what was recorded and how ([lecture]: when, how long, the audio file),
 * [audioBytes] (the file's size), what recording took ([recording]: power, battery, temperature, CPU, the audio's
 * format and encoder, the microphone, the models) and, for "WAV, then encode", what encoding it afterwards took
 * ([encoding]). The reports are null for moments recorded before they were kept, or on the charger's figures.
 */
data class MomentInfo(
    val lecture: Lecture,
    val audioBytes: Long?,
    val recording: PowerReport?,
    val encoding: PowerReport?,
)
