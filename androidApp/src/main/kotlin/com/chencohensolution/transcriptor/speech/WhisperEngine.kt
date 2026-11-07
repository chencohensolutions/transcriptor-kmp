package com.chencohensolution.transcriptor.speech

import java.io.File

/**
 * whisper.cpp (src/main/cpp) with a model file loaded: slow to create (the file is read into memory), then reused.
 * With [npuEncoder] (an `encoder.bin` for this phone's NPU, [com.chencohensolution.transcriptor.models.ModelKind.WhisperNpuEncoder])
 * the encoder runs on the NPU through Qualcomm's runtime in [libDir] (the app's native libraries) and whisper.cpp
 * decodes; every piece is then a 30 s window to the encoder.
 */
class WhisperEngine(path: String, npuEncoder: File? = null, libDir: String? = null) : SpeechEngine {
    private var handle = run {
        if (npuEncoder != null) {
            SherpaNpuWhisperEngine.useNpuLibraries(checkNotNull(libDir))
            // The encoder's C API is in sherpa-onnx's library; whisper's JNI finds it there.
            System.loadLibrary("sherpa-onnx-jni")
        }
        nativeInit(path, npuEncoder?.path, libDir?.let { "$it/libQnnHtp.so" }, libDir?.let { "$it/libQnnSystem.so" })
    }.also { check(it != 0L) { if (npuEncoder != null) "Couldn't load the model or its NPU encoder ($path)" else "Couldn't load the model ($path)" } }
    private val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    override fun transcribe(samples: FloatArray, language: String, prompt: String?): List<Piece> {
        check(handle != 0L) { "Closed" }
        val lines = nativeTranscribe(handle, samples, language, prompt, threads) ?: error("whisper failed")
        return lines.map { line ->
            val (start, end, noSpeech, text) = line.split('\t', limit = 4)
            Piece(start.toLong(), end.toLong(), text, noSpeech.toFloat())
        }
    }

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0
    }

    companion object {
        init {
            System.loadLibrary("transcriptor_whisper")
        }

        /** Shrinks a model file: [type] is a ggml type ("q8_0"). Slow (seconds to a minute); not on the main thread. */
        fun quantize(input: String, output: String, type: String) = nativeQuantize(input, output, type)

        @JvmStatic private external fun nativeQuantize(input: String, output: String, type: String): Boolean
        @JvmStatic private external fun nativeInit(path: String, encoder: String?, backend: String?, system: String?): Long
        @JvmStatic private external fun nativeFree(handle: Long)
        @JvmStatic private external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String, prompt: String?, threads: Int): Array<String>?
    }
}
