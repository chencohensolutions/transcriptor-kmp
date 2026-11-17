package com.chencohensolution.transcriptor.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.chencohensolution.transcriptor.settings.AacSettings
import com.chencohensolution.transcriptor.settings.FlacFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okio.FileSystem
import okio.Path

/**
 * The phone's microphone, 48 kHz stereo 16 bit (or floating point for a 24-bit WAV or FLAC), read on its own thread
 * into a [WavWriter], a [FlacEncoder] or an [AacEncoder]. Live transcription and the meter always get 16 bits.
 * Needs the RECORD_AUDIO permission, and a foreground service of the microphone type to go on with the screen off.
 */
class AndroidRecorder(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    /** The microphones besides the phone's own; null: always the phone's own. */
    private val inputs: AndroidAudioInputs? = null,
) : AudioRecorder {
    private val routed = MutableStateFlow<InputInUse?>(null)
    override val inputInUse: StateFlow<InputInUse?> = routed
    private var communication = false
    private val routing = AudioRecord.OnRoutingChangedListener { r -> routed.value = r.routedDevice?.let(::inputOf) }
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
    /** The sink's bit depth: 24 reads the microphone as floats. */
    private var bits = 16
    // Kept after stop, for the power report.
    @Volatile override var encoderName: String? = null
        private set
    @Volatile override var encoderHardware: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    override fun start(file: Path, format: AudioFileFormat, flacLevel: Int, aac: AacSettings, input: MicInput?, flac: FlacFormat) {
        check(record == null) { "Already recording" }
        val lossless = flac.valid()
        bits = if (format == AudioFileFormat.Aac) 16 else lossless.bitDepth
        sink = when (format) {
            AudioFileFormat.Wav -> WavWriter(fileSystem, file, bits = bits)
            AudioFileFormat.Flac -> FlacEncoder(file.toFile(), flacLevel, lossless)
            AudioFileFormat.Aac -> AacEncoder(file.toFile(), aac)
        }
        encoderName = when (val s = sink) {
            is FlacEncoder -> s.encoderName
            is AacEncoder -> s.encoderName
            else -> null
        }
        encoderHardware = AacEncoders.all.any { it.name == encoderName && it.hardware }
        val encoding = if (bits == 24) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, encoding)
        // A picked input that is connected: a Bluetooth headset's microphone opens only as the communication device,
        // recorded as a call's voice (mono, the headset's own noise reduction); a wired or USB one is just preferred.
        // Asked for in 48 kHz stereo like the phone's own, the phone converts it, so the file is the same either way.
        val device = input?.let { inputs?.find(it) }
        val bluetooth = device != null && AndroidAudioInputs.kindOf(device.type).let { it == MicInput.Kind.Bluetooth || it == MicInput.Kind.BluetoothLe }
        communication = bluetooth && inputs?.useForCommunication(device!!) == true
        record = AudioRecord(
            if (communication) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC,
            RATE, AudioFormat.CHANNEL_IN_STEREO, encoding,
            maxOf(minimum * 4, RATE * BYTES_PER_FRAME * (if (bits == 24) 2 else 1) / 2),
        ).apply {
            if (device != null) preferredDevice = device
            addOnRoutingChangedListener(routing, null)
        }
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
        routed.value = record.routedDevice?.let(::inputOf)
        reading = true
        thread = Thread({ readLoop(record) }, "recorder").apply { start() }
    }

    override fun checkpoint() {
        synchronized(lock) { sink?.checkpoint() }
    }

    override fun stop() {
        stopReading()
        record?.run { removeOnRoutingChangedListener(routing); stop(); release() }
        record = null
        if (communication) inputs?.clearCommunication()
        communication = false
        routed.value = null
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
        val floats = if (bits == 24) FloatArray(RATE / 50 * 2) else null
        val wide = if (bits == 24) ByteArray(RATE / 50 * 2 * 3) else null
        while (reading) {
            val read: Int
            if (floats != null) {
                val samples = record.read(floats, 0, floats.size, AudioRecord.READ_BLOCKING)
                if (samples <= 0) continue
                val whole = samples - samples % 2
                val n24 = PcmConvert.floatTo24(floats, whole, wide!!)
                synchronized(lock) { sink?.write(wide, n24) }
                read = PcmConvert.floatTo16(floats, whole, buffer)
            } else {
                read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                synchronized(lock) { sink?.write(buffer, read) }
            }
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


    /** The routed device as the screen names it; null for the phone's own microphones. */
    private fun inputOf(device: android.media.AudioDeviceInfo): InputInUse? =
        with(AndroidAudioInputs) { device.toInput() }?.let { InputInUse(it.name, it.kind) }

    private companion object {
        const val RATE = WavWriter.SAMPLE_RATE
        const val BYTES_PER_FRAME = WavWriter.CHANNELS * 2
    }
}
