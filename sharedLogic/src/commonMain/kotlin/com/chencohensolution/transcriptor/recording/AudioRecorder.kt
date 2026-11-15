package com.chencohensolution.transcriptor.recording

import kotlinx.coroutines.flow.StateFlow
import com.chencohensolution.transcriptor.settings.AacSettings
import okio.Path

/** How the recorder writes the audio while recording. */
enum class AudioFileFormat { Wav, Flac, Aac }

/**
 * The microphone, written to a file as it records: 48 kHz, stereo, 16 bit. One per platform (Android's in
 * androidMain); tests use a fake.
 */
interface AudioRecorder {
    /** The position in the audio, from the samples written: pauses don't count, so every time points into the file. */
    val positionMillis: StateFlow<Long>

    /** The sound level meter, both channels, about 10 times a second; silent when paused or stopped. */
    val meter: StateFlow<StereoMeterState>

    /** The meter also works out the frequency bands: only while a frequency style is on screen (it costs a small FFT). */
    var wantBands: Boolean

    /** Gets every buffer written (48 kHz stereo 16 bit), on the recorder's thread: live transcription. */
    var onPcm: ((pcm: ByteArray, size: Int) -> Unit)?

    /**
     * Starts recording to [file]; a FLAC is encoded at [flacLevel] (0 the fastest, 8 the smallest), an AAC as [aac]
     * says. From [input] when it is connected, else from the phone's own microphones.
     */
    fun start(file: Path, format: AudioFileFormat, flacLevel: Int, aac: AacSettings = AacSettings(), input: MicInput? = null)
    fun pause()
    fun resume()

    /** Makes what is written so far readable after a crash (a WAV's header sizes). */
    fun checkpoint()
    fun stop()

    /** The input the phone routed the recording to (it changes when a headset disconnects); null: the phone's own. */
    val inputInUse: StateFlow<InputInUse?>

    /** The encoder the recording runs on (MediaCodec's name); null for WAV, or before [start]. */
    val encoderName: String? get() = null

    /** [encoderName] runs on dedicated hardware rather than the CPU. */
    val encoderHardware: Boolean get() = false
}

/** Where the recorder's PCM (16 bit, interleaved) goes: a WAV file, or a FLAC encoder. */
interface PcmSink {
    fun write(pcm: ByteArray, size: Int)
    fun checkpoint()
    fun close()
}
