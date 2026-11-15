package com.chencohensolution.transcriptor.recording

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Both channels of the recording at one moment, each 0..1 on the meter's dB scale (-60 dBFS .. 0), with a peak that
 * holds and then falls slowly, so a short loud sound stays visible for a moment.
 */
data class StereoLevel(val left: Float, val right: Float, val leftPeak: Float = left, val rightPeak: Float = right) {
    /** The louder channel: the single level of a mono meter. */
    val level get() = max(left, right)

    companion object {
        val Silent = StereoLevel(0f, 0f)
    }
}

/**
 * The sound level meter's state, about 10 times a second: [now], the last [METER_HISTORY] readings ([history], oldest
 * first) and, only while a frequency style is on screen ([MeterAnalyzer.wantBands]), the voice range in [METER_BANDS]
 * bands (low to high, 0..1) with their own history. [stereo] turns true once the two channels have differed: a phone
 * that copies one microphone into both channels shows one meter.
 */
data class StereoMeterState(
    val now: StereoLevel = StereoLevel.Silent,
    val history: List<StereoLevel> = emptyList(),
    val bands: List<Float> = emptyList(),
    val bandHistory: List<List<Float>> = emptyList(),
    val stereo: Boolean = false,
) {
    /** Paused: nothing is heard, the strips stay as they were. */
    fun silenced() = copy(now = StereoLevel.Silent, bands = bands.map { 0f })
}

/** How many bands the frequency styles split [BAND_LOW_HZ] .. [BAND_HIGH_HZ] into, evenly on a log scale. */
const val METER_BANDS = 24

/** How many readings (at 10 Hz) the strips keep: 8 seconds. */
const val METER_HISTORY = 80

const val BAND_LOW_HZ = 80f
const val BAND_HIGH_HZ = 8000f

/** The centre of band [i] of [METER_BANDS], on a log scale from [BAND_LOW_HZ] to [BAND_HIGH_HZ]. */
fun bandCenter(i: Int): Float = BAND_LOW_HZ * exp(ln(BAND_HIGH_HZ / BAND_LOW_HZ) * i / (METER_BANDS - 1))

/**
 * Works out the meter from the recorder's PCM (16 bit, interleaved stereo), on the recorder's thread. Every buffer goes
 * to [feed] (it only keeps samples while [wantBands]); [read] makes a reading from the latest buffer, about 10 times a
 * second. The levels are two sums over a buffer; the bands are a 2048-point FFT of the last ~43 ms, only while
 * [wantBands] (a frequency style is on screen), so the bars style costs next to nothing.
 */
class MeterAnalyzer(private val sampleRate: Int = WavWriter.SAMPLE_RATE) {
    @kotlin.concurrent.Volatile var wantBands = false

    private val ring = FloatArray(FFT_SIZE)
    private var at = 0
    private var filled = 0
    private var leftPeak = 0f
    private var rightPeak = 0f
    private var stereo = false
    var state = StereoMeterState()
        private set

    fun feed(pcm: ByteArray, size: Int) {
        if (!wantBands) {
            filled = 0
            return
        }
        var i = 0
        while (i + 3 < size) {
            ring[at] = (sample(pcm, i) + sample(pcm, i + 2)) / (2f * Short.MAX_VALUE)
            at = (at + 1) % FFT_SIZE
            i += 4
        }
        filled = minOf(FFT_SIZE, filled + size / 4)
    }

    fun read(pcm: ByteArray, size: Int): StereoMeterState {
        var left = 0.0
        var right = 0.0
        var i = 0
        while (i + 3 < size) {
            val l = sample(pcm, i)
            val r = sample(pcm, i + 2)
            if (l != r) stereo = true
            left += l.toDouble() * l
            right += r.toDouble() * r
            i += 4
        }
        val frames = (size / 4).coerceAtLeast(1)
        val l = scale(sqrt(left / frames) / Short.MAX_VALUE)
        val r = scale(sqrt(right / frames) / Short.MAX_VALUE)
        leftPeak = max(l, leftPeak - PEAK_FALL)
        rightPeak = max(r, rightPeak - PEAK_FALL)
        val now = StereoLevel(l, r, leftPeak, rightPeak)
        val bands = if (wantBands && filled == FFT_SIZE) bands() else emptyList()
        state = StereoMeterState(
            now = now,
            history = (state.history + now).takeLast(METER_HISTORY),
            bands = bands,
            bandHistory = if (bands.isEmpty()) state.bandHistory else (state.bandHistory + listOf(bands)).takeLast(METER_HISTORY),
            stereo = stereo,
        )
        return state
    }

    /** A new recording: nothing heard yet, mono until the channels differ. */
    fun reset() {
        leftPeak = 0f
        rightPeak = 0f
        stereo = false
        filled = 0
        state = StereoMeterState()
    }

    /** A pause: the meter drops to silence, the strips keep what they had. */
    fun silence(): StereoMeterState {
        leftPeak = 0f
        rightPeak = 0f
        state = state.silenced()
        return state
    }

    /** The voice range now, [METER_BANDS] values 0..1 (-70 dBFS .. -10), from the last [FFT_SIZE] samples. */
    private fun bands(): List<Float> {
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        for (k in 0 until FFT_SIZE) re[k] = ring[(at + k) % FFT_SIZE] * window[k]
        fft(re, im)
        val binHz = sampleRate.toFloat() / FFT_SIZE
        val ratio = exp(ln(BAND_HIGH_HZ / BAND_LOW_HZ) / (2 * (METER_BANDS - 1))) // half a band, on the log scale
        return List(METER_BANDS) { b ->
            val centre = bandCenter(b)
            val from = (centre / ratio / binHz).toInt().coerceAtLeast(1)
            val to = (centre * ratio / binHz).toInt().coerceAtLeast(from)
            var power = 0f
            for (k in from..to) power = max(power, re[k] * re[k] + im[k] * im[k])
            // A full-scale sine peaks at FFT_SIZE / 4 through the Hann window.
            val db = 10 * log10(max(power, 1e-12f) / (FFT_SIZE / 4f).let { it * it })
            ((db + 70) / 60).coerceIn(0f, 1f)
        }
    }

    private companion object {
        const val FFT_SIZE = 2048
        const val PEAK_FALL = 0.025f
        val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }

        fun sample(pcm: ByteArray, i: Int) = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)

        /** RMS (0..1 of full scale) on the meter's dB scale: silence about 0, a voice near the phone 0.5–0.9. */
        fun scale(rms: Double): Float {
            if (rms <= 0) return 0f
            return ((20 * log10(rms) + 60) / 60).toFloat().coerceIn(0f, 1f)
        }

        /** In-place radix-2 FFT; [re] and [im] are [FFT_SIZE] long. */
        fun fft(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    val t = re[i]; re[i] = re[j]; re[j] = t
                    val u = im[i]; im[i] = im[j]; im[j] = u
                }
            }
            var len = 2
            while (len <= n) {
                val angle = -2 * PI / len
                val wr = cos(angle).toFloat()
                val wi = sin(angle).toFloat()
                var i = 0
                while (i < n) {
                    var cr = 1f
                    var ci = 0f
                    for (k in 0 until len / 2) {
                        val a = i + k
                        val b = a + len / 2
                        val tr = re[b] * cr - im[b] * ci
                        val ti = re[b] * ci + im[b] * cr
                        re[b] = re[a] - tr; im[b] = im[a] - ti
                        re[a] += tr; im[a] += ti
                        val nr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
