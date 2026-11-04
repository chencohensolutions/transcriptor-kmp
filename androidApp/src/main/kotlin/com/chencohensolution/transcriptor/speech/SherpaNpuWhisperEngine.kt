package com.chencohensolution.transcriptor.speech

import android.os.Build
import android.system.Os
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import java.io.File

/**
 * Whisper on a Qualcomm NPU: sherpa-onnx's QNN backend with the encoder and decoder context binaries in [dir] and
 * Qualcomm's runtime libraries in [libDir] (the app's native libraries, unpacked so the NPU can load its part;
 * the manifest asks for the phone's libcdsprpc.so, which they need).
 * Slow to create (seconds: the graphs are loaded onto the NPU), then reused; created again when the language changes.
 * The whole of each call is one [Piece]: no times inside it, no prompt, and no estimate of silence.
 */
class SherpaNpuWhisperEngine(private val dir: File, private val libDir: String) : SpeechEngine {
    private var language: String? = null
    private var recognizer: OfflineRecognizer? = null

    override fun transcribe(samples: FloatArray, language: String, prompt: String?): List<Piece> {
        val recognizer = recognizerFor(language)
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16_000)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            return if (text.isEmpty()) emptyList() else listOf(Piece(0, samples.size / 16L, text))
        } finally {
            stream.release()
        }
    }

    /** Loads the model onto the NPU for [language] now (seconds), instead of on the first call; throws if it can't. */
    fun load(language: String) {
        recognizerFor(language)
    }

    private fun recognizerFor(language: String): OfflineRecognizer {
        recognizer?.takeIf { language == this.language }?.let { return it }
        recognizer?.release()
        recognizer = null
        useNpuLibraries(libDir)
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        language = language,
                        task = "transcribe",
                        qnnConfig = QnnConfig(
                            backendLib = "$libDir/libQnnHtp.so",
                            contextBinary = "${File(dir, "encoder.bin").path},${File(dir, "decoder.bin").path}",
                            systemLib = "$libDir/libQnnSystem.so",
                        ),
                    ),
                    tokens = File(dir, "tokens.txt").path,
                    numThreads = 2,
                    provider = "qnn",
                    modelType = "whisper",
                ),
            ),
        ).also {
            recognizer = it
            this.language = language
        }
    }

    override fun close() {
        recognizer?.release()
        recognizer = null
    }

    companion object {
        /** The phone's chip ("SM8750"), for the models compiled for one; null before Android 12. */
        val chip: String? get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null

        /** Where the NPU's side of Qualcomm's runtime (libQnnHtpV79Skel.so) is looked for: [libDir], then the system's. */
        fun useNpuLibraries(libDir: String) {
            Os.setenv("ADSP_LIBRARY_PATH", "$libDir;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp", true)
        }
    }
}
