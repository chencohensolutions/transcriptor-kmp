package com.chencohensolution.transcriptor.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.chencohensolution.transcriptor.settings.AacSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okio.FileSystem
import okio.Path

/**
 * The phone's microphone, 48 kHz stereo 16 bit, read on its own thread into a [WavWriter], a [FlacEncoder] or an [AacEncoder].
 * Needs the RECORD_AUDIO permission, and a foreground service of the microphone type to go on with the screen off.
 */
class AndroidRecorder(private val fileSystem: FileSystem = FileSystem.SYSTEM) : AudioRecorder {
    private val position = MutableStateFlow(0L)
    private val analyzer = MeterAnalyzer(RATE)
    private val meterFlow = MutableStateFlow(StereoMeterState())
    override val positionMillis: StateFlow<Long> = position
    override val meter: StateFlow<StereoMeterState> = meterFlow
    override var wantBands: Boolean
        get() = analyzer.wantBands
        set(value) { analyzer.wantBands = value }
    @Volatile override var onPcm: ((pcm: ByteArray, size: Int) -> Unit)? = null

    private val lock = Any()
    private var record: AudioRecord? = null
    private var sink: PcmSink? = null
    private var thread: Thread? = null
    @Volatile private var reading = false
    private var frames = 0L
    private var reads = 0
    // Kept after stop, for the power report.
    @Volatile override var encoderName: String? = null
        private set
    @Volatile override var encoderHardware: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    override fun start(file: Path, format: AudioFileFormat, flacLevel: Int, aac: AacSettings) {
        check(record == null) { "Already recording" }
        sink = when (format) {
            AudioFileFormat.Wav -> WavWriter(fileSystem, file)
            AudioFileFormat.Flac -> FlacEncoder(file.toFile(), flacLevel)
            AudioFileFormat.Aac -> AacEncoder(file.toFile(), aac)
        }
        encoderName = when (val s = sink) {
            is FlacEncoder -> s.encoderName
            is AacEncoder -> s.encoderName
            else -> null
        }
        encoderHardware = AacEncoders.all.any { it.name == encoderName && it.hardware }
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        record = AudioRecord(
            MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum * 4, RATE * BYTES_PER_FRAME / 2),
        )
        frames = 0
        position.value = 0
        analyzer.reset()
        meterFlow.value = analyzer.state
        resume()
    }

    override fun pause() {
        stopReading()
        record?.stop()
        meterFlow.value = analyzer.silence()
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
        meterFlow.value = analyzer.silence()
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
            onPcm?.invoke(buffer, read)
            analyzer.feed(buffer, read)
            frames += read / BYTES_PER_FRAME
            // The clock and the meter at 10 Hz: every buffer (50 Hz) would redraw the screen for nothing.
            if (++reads % 5 == 0) {
                position.value = frames * 1000 / RATE
                meterFlow.value = analyzer.read(buffer, read)
            }
        }
        position.value = frames * 1000 / RATE
    }


    private companion object {
        const val RATE = WavWriter.SAMPLE_RATE
        const val BYTES_PER_FRAME = WavWriter.CHANNELS * 2
    }
}
