package com.chencohensolution.transcriptor.recording

import kotlinx.coroutines.flow.StateFlow
import okio.Path

/** How the recorder writes the audio while recording. */
enum class AudioFileFormat { Wav, Flac }

/**
 * The microphone, written to a file as it records: 48 kHz, stereo, 16 bit. One per platform (Android's in
 * androidMain); tests use a fake.
 */
interface AudioRecorder {
    /** The position in the audio, from the samples written: pauses don't count, so every time points into the file. */
    val positionMillis: StateFlow<Long>

    /** The microphone's level, 0..1; 0 when paused or stopped. */
    val level: StateFlow<Float>

    /** Gets every buffer written (48 kHz stereo 16 bit), on the recorder's thread: live transcription. */
    var onPcm: ((pcm: ByteArray, size: Int) -> Unit)?

    fun start(file: Path, format: AudioFileFormat)
    fun pause()
    fun resume()

    /** Makes what is written so far readable after a crash (a WAV's header sizes). */
    fun checkpoint()
    fun stop()
}

/** Where the recorder's PCM (16 bit, interleaved) goes: a WAV file, or a FLAC encoder. */
interface PcmSink {
    fun write(pcm: ByteArray, size: Int)
    fun checkpoint()
    fun close()
}
