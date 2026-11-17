package com.chencohensolution.transcriptor.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings › Microphone's test: listens to [input] (null: the phone's own, stereo) the way a recording would, nothing
 * kept, and gives both channels' levels about 10 times a second, so scratching near a microphone shows which one hears.
 * Needs RECORD_AUDIO; not while a moment records (the recording has the microphone).
 */
class MicProbe(private val inputs: AndroidAudioInputs) {
    private val levels = MutableStateFlow(StereoMeterState())
    val meter: StateFlow<StereoMeterState> = levels
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /** Starts listening to [input], after stopping what listened before. False when the microphone wouldn't open. */
    @SuppressLint("MissingPermission")
    fun start(input: MicInput?): Boolean {
        stop()
        val device = input?.let { inputs.find(it) }
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2, RATE / 5 * 4))
                .apply { if (device != null) preferredDevice = device }
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return false
        record = r
        running = true
        val analyzer = MeterAnalyzer(RATE)
        r.startRecording()
        thread = Thread({
            val buffer = ByteArray(RATE / 10 * 4)
            while (running) {
                val n = r.read(buffer, 0, buffer.size)
                if (n > 0) levels.value = analyzer.read(buffer, n)
            }
        }, "mic-probe").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join()
        thread = null
        record?.run { runCatching { stop() }; release() }
        record = null
        levels.value = StereoMeterState()
    }

    private companion object {
        const val RATE = 48_000
    }
}
