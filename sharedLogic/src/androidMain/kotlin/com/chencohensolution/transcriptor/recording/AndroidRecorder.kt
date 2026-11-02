package com.chencohensolution.transcriptor.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okio.FileSystem
import okio.Path
import kotlin.math.sqrt

/**
 * The phone's microphone, 48 kHz stereo 16 bit, read on its own thread into a [WavWriter] or a [FlacEncoder].
 * Needs the RECORD_AUDIO permission, and a foreground service of the microphone type to go on with the screen off.
 */
class AndroidRecorder(private val fileSystem: FileSystem = FileSystem.SYSTEM) : AudioRecorder {
    private val position = MutableStateFlow(0L)
    private val meter = MutableStateFlow(0f)
    override val positionMillis: StateFlow<Long> = position
    override val level: StateFlow<Float> = meter

    private val lock = Any()
    private var record: AudioRecord? = null
    private var sink: PcmSink? = null
    private var thread: Thread? = null
    @Volatile private var reading = false
    private var frames = 0L

    @SuppressLint("MissingPermission")
    override fun start(file: Path, format: AudioFileFormat) {
        check(record == null) { "Already recording" }
        sink = when (format) {
            AudioFileFormat.Wav -> WavWriter(fileSystem, file)
            AudioFileFormat.Flac -> FlacEncoder(file.toFile())
        }
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        record = AudioRecord(
            MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum * 4, RATE * BYTES_PER_FRAME / 2),
        )
        frames = 0
        position.value = 0
        resume()
    }

    override fun pause() {
        stopReading()
        record?.stop()
        meter.value = 0f
    }

    override fun resume() {
        val record = record ?: return
        if (reading) return
        record.startRecording()
        reading = true
        thread = Thread({ readLoop(record) }, "recorder").apply { start() }
    }

    override fun checkpoint() {
        synchronized(lock) { sink?.checkpoint() }
    }

    override fun stop() {
        stopReading()
        record?.run { stop(); release() }
        record = null
        synchronized(lock) { sink?.close() }
        sink = null
        meter.value = 0f
    }

    private fun stopReading() {
        reading = false
        thread?.join()
        thread = null
    }

    private fun readLoop(record: AudioRecord) {
        // 20 ms a read: the meter moves smoothly and a pause stops within a read.
        val buffer = ByteArray(RATE / 50 * BYTES_PER_FRAME)
        while (reading) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue
            synchronized(lock) { sink?.write(buffer, read) }
            frames += read / BYTES_PER_FRAME
            position.value = frames * 1000 / RATE
            meter.value = levelOf(buffer, read)
        }
    }

    /** RMS of the buffer on a rough dB scale, 0..1 (silence about 0, a voice near the phone 0.5–0.9). */
    private fun levelOf(pcm: ByteArray, size: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < size) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            sum += sample.toDouble() * sample
            i += 2
        }
        val rms = sqrt(sum / (size / 2)) / Short.MAX_VALUE
        if (rms <= 0) return 0f
        val db = 20 * kotlin.math.log10(rms)
        return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val RATE = WavWriter.SAMPLE_RATE
        const val BYTES_PER_FRAME = WavWriter.CHANNELS * 2
    }
}
