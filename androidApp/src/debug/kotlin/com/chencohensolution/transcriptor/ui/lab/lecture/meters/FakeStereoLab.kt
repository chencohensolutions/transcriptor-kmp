package com.chencohensolution.transcriptor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

// Fakes: the microphone. A voice made up at 10 Hz: syllables about four a second, words in runs with short pauses
// between them, a room's hiss under it; the right channel a little quieter than the left (the speaker nearer one mic),
// now and then the other way round (someone across the table). The bands are a voice's shape (the pitch, two
// formants that move, a "s" now and then), not an FFT of real sound: real rooms are noisier and less tidy.
@Composable
internal fun rememberFakeStereo(paused: Boolean): StereoMeterState {
    val silent = StereoLevel(0f, 0f)
    var state by remember { mutableStateOf(StereoMeterState(silent, List(METER_HISTORY) { silent }, List(METER_BANDS) { 0f }, List(METER_HISTORY) { List(METER_BANDS) { 0f } })) }
    LaunchedEffect(paused) {
        if (paused) {
            state = state.copy(now = silent, bands = List(METER_BANDS) { 0f })
            return@LaunchedEffect
        }
        val random = Random(11)
        var tick = 0
        var talking = 0 // readings left in this run of words, or of the pause after it
        var inRun = true
        var balance = 0.82f // right / left
        var lPeak = 0f
        var rPeak = 0f
        while (true) {
            delay(100)
            tick++
            if (--talking <= 0) {
                inRun = !inRun
                talking = if (inRun) 15 + random.nextInt(30) else 3 + random.nextInt(8)
                if (inRun && random.nextFloat() < 0.25f) balance = if (balance < 1f) 1.18f else 0.82f
            }
            val syllable = max(0f, sin(tick * 2.6f + sin(tick * 0.37f) * 1.4f))
            val voice = if (inRun) 0.35f + 0.5f * syllable * (0.75f + 0.25f * random.nextFloat()) else 0f
            val hiss = 0.12f + 0.04f * random.nextFloat()
            val left = (max(voice, hiss) * if (balance > 1f) 1f / balance else 1f).coerceIn(0f, 1f)
            val right = (max(voice * balance, hiss)).coerceIn(0f, 1f)
            lPeak = max(left, lPeak - 0.025f)
            rPeak = max(right, rPeak - 0.025f)
            val now = StereoLevel(left, right, lPeak, rPeak)

            val pitch = 130f + 40f * sin(tick * 0.11f)
            val f1 = 550f + 250f * sin(tick * 1.3f)
            val f2 = 1700f + 600f * sin(tick * 0.9f + 1f)
            val hush = inRun && random.nextFloat() < 0.08f
            val bands = List(METER_BANDS) { i ->
                val f = bandCenter(i)
                val shape = bump(f, pitch, 0.25f) * 0.9f + bump(f, f1, 0.35f) + bump(f, f2, 0.3f) * 0.75f + (if (hush) bump(f, 6000f, 0.25f) * 0.8f else 0f)
                (0.06f + 0.04f * random.nextFloat() + (voice * shape).coerceAtMost(1f) * 0.9f).coerceIn(0f, 1f)
            }
            state = StereoMeterState(now, (state.history + now).takeLast(METER_HISTORY), bands, (state.bandHistory + listOf(bands)).takeLast(METER_HISTORY))
        }
    }
    return state
}

/** The centre of band [i] of [METER_BANDS], 80 Hz .. 8 kHz on a log scale. */
internal fun bandCenter(i: Int): Float = 80f * exp(ln(100f) * i / (METER_BANDS - 1))

private fun bump(f: Float, centre: Float, width: Float): Float {
    val d = ln(f / centre) / width
    return exp(-d * d)
}

/** A meter option shown in the glance as hen saw it: recording only (After), the fake voice in [meter]. */
internal fun meterOption(id: String, title: String, note: String, meter: @Composable (StereoMeterState) -> Unit) = LabOption(id, title, note) {
    val (state, actions) = rememberGrowingLectureLab()
    val stereo = rememberFakeStereo(state.paused)
    MeterGlanceLab(
        state.copy(
            lecture = state.lecture.copy(segments = emptyList(), speakers = emptyList(), pendingTranscript = true),
            partial = null, liveModelName = null, lagMillis = null, voicePrints = null, speakerPassOnStop = false, speakersLive = false, recordOnly = true,
        ),
        actions, onTranscript = {},
    ) { meter(stereo) }
}
