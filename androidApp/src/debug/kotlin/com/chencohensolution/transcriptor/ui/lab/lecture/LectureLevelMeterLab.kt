package com.chencohensolution.transcriptor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.transcriptor.recording.METER_BANDS
import com.chencohensolution.transcriptor.recording.METER_HISTORY
import com.chencohensolution.transcriptor.recording.StereoLevel
import com.chencohensolution.transcriptor.recording.StereoMeterState
import com.chencohensolution.transcriptor.recording.bandCenter
import com.chencohensolution.transcriptor.settings.LevelMeterStyle
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
    var state by remember { mutableStateOf(StereoMeterState(silent, List(METER_HISTORY) { silent }, List(METER_BANDS) { 0f }, List(METER_HISTORY) { List(METER_BANDS) { 0f } }, stereo = true)) }
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
            state = StereoMeterState(now, (state.history + now).takeLast(METER_HISTORY), bands, (state.bandHistory + listOf(bands)).takeLast(METER_HISTORY), stereo = true)
        }
    }
    return state
}


/**
 * The app's glance with the sound meter in [style] (Settings › While recording), fed the fake voice; recording only
 * (After), as hen saw it when asking for two meters.
 */
private fun meterOption(style: LevelMeterStyle, title: String) = LabOption("current-meter-${style.key}", title, "LectureScreen's glance, recording only, with a faked stereo microphone") {
    val (state, actions) = rememberGrowingLectureLab()
    val stereo = rememberFakeStereo(state.paused)
    LectureScreen(
        state.copy(
            lecture = state.lecture.copy(segments = emptyList(), speakers = emptyList(), pendingTranscript = true),
            partial = null, liveModelName = null, lagMillis = null, voicePrints = null, speakerPassOnStop = false, speakersLive = false, recordOnly = true,
            level = stereo.now.level, stereoMeter = stereo, levelMeter = style,
        ),
        actions,
    )
}

internal val lectureCurrentMeterBars = meterOption(LevelMeterStyle.Bars, "Sound meter: five bars per channel (the app's default)")
internal val lectureCurrentMeterLines = meterOption(LevelMeterStyle.Lines, "Sound meter: a bar per channel with a peak (the app's)")
internal val lectureCurrentMeterStrip = meterOption(LevelMeterStyle.Strip, "Sound meter: the last 8 seconds, L above R below (the app's)")
internal val lectureCurrentMeterSpectrum = meterOption(LevelMeterStyle.Spectrum, "Sound meter: voice spectrum (the app's)")
internal val lectureCurrentMeterSpectrogram = meterOption(LevelMeterStyle.Spectrogram, "Sound meter: spectrogram (the app's)")

private fun bump(f: Float, centre: Float, width: Float): Float {
    val d = ln(f / centre) / width
    return exp(-d * d)
}

// Fakes: as `current-meter-bars`, recording from Bluetooth earbuds picked in Settings: mono, so one set of bars.
internal val lectureCurrentMic = LabOption("current-mic", "Recording from Bluetooth earbuds (the app's)", "The glance names the microphone when it isn't the phone's own") {
    val (state, actions) = rememberGrowingLectureLab()
    val stereo = rememberFakeStereo(state.paused).let { it.copy(stereo = false, now = it.now.copy(right = it.now.left, rightPeak = it.now.leftPeak)) }
    LectureScreen(
        state.copy(
            lecture = state.lecture.copy(segments = emptyList(), speakers = emptyList(), pendingTranscript = true),
            partial = null, liveModelName = null, lagMillis = null, voicePrints = null, speakerPassOnStop = false, speakersLive = false, recordOnly = true,
            level = stereo.now.level, stereoMeter = stereo,
            input = com.chencohensolution.transcriptor.recording.InputInUse("Galaxy Buds3 Pro", com.chencohensolution.transcriptor.recording.MicInput.Kind.Bluetooth),
        ),
        actions,
    )
}

// Fakes: as `current-meter-bars`, with earbuds picked in Settings that aren't connected: the phone records.
internal val lectureCurrentMicMissing = LabOption("current-mic-missing", "The picked earbuds aren't connected (the app's)", "The phone records, and the glance says so") {
    val (state, actions) = rememberGrowingLectureLab()
    val stereo = rememberFakeStereo(state.paused)
    LectureScreen(
        state.copy(
            lecture = state.lecture.copy(segments = emptyList(), speakers = emptyList(), pendingTranscript = true),
            partial = null, liveModelName = null, lagMillis = null, voicePrints = null, speakerPassOnStop = false, speakersLive = false, recordOnly = true,
            level = stereo.now.level, stereoMeter = stereo, inputMissing = "Galaxy Buds3 Pro",
        ),
        actions,
    )
}
