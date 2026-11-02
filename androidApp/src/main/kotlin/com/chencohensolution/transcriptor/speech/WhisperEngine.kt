package com.chencohensolution.transcriptor.speech

/** whisper.cpp (src/main/cpp) with a model file loaded: slow to create (the file is read into memory), then reused. */
class WhisperEngine(path: String) : SpeechEngine {
    private var handle = nativeInit(path).also { check(it != 0L) { "Couldn't load the model ($path)" } }
    private val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    override fun transcribe(samples: FloatArray, language: String, prompt: String?): List<Piece> {
        check(handle != 0L) { "Closed" }
        val lines = nativeTranscribe(handle, samples, language, prompt, threads) ?: error("whisper failed")
        return lines.map { line ->
            val (start, end, text) = line.split('\t', limit = 3)
            Piece(start.toLong(), end.toLong(), text)
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
        @JvmStatic private external fun nativeInit(path: String): Long
        @JvmStatic private external fun nativeFree(handle: Long)
        @JvmStatic private external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String, prompt: String?, threads: Int): Array<String>?
    }
}
