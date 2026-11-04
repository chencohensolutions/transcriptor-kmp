package com.chencohensolution.transcriptor.speech

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import java.io.File

/**
 * A streaming transducer (encoder, decoder, joiner, tokens in [dir]) on sherpa-onnx's online recognizer: on the CPU
 * (int8 ONNX files), or with [npuLibDir] on a Qualcomm NPU (context binaries `encoder.bin`, `decoder.bin`,
 * `joiner.bin`, and Qualcomm's runtime in [npuLibDir]; seconds to load). A line ends at sherpa-onnx's endpoint:
 * [PAUSE_AFTER_SPEECH_S] of quiet after words, or [MAX_LINE_S].
 */
class SherpaStreamingEngine(dir: File, npuLibDir: String? = null) : StreamingEngine {
    private val recognizer = OnlineRecognizer(
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0f),
            modelConfig = OnlineModelConfig(
                transducer = if (npuLibDir == null) {
                    OnlineTransducerModelConfig(
                        encoder = File(dir, "encoder.int8.onnx").path,
                        decoder = File(dir, "decoder.int8.onnx").path,
                        joiner = File(dir, "joiner.int8.onnx").path,
                    )
                } else {
                    SherpaNpuWhisperEngine.useNpuLibraries(npuLibDir)
                    OnlineTransducerModelConfig(
                        qnnConfig = QnnConfig(
                            backendLib = "$npuLibDir/libQnnHtp.so",
                            contextBinary = listOf("encoder", "decoder", "joiner").joinToString(",") { File(dir, "$it.bin").path },
                            systemLib = "$npuLibDir/libQnnSystem.so",
                        ),
                    )
                },
                tokens = File(dir, "tokens.txt").path,
                numThreads = 2,
                provider = if (npuLibDir == null) "cpu" else "qnn",
                modelType = "zipformer2",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, PAUSE_AFTER_SPEECH_S, 0f),
                rule3 = EndpointRule(false, 0f, MAX_LINE_S),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        ),
    )
    private val stream = recognizer.createStream()

    override fun accept(samples: FloatArray) {
        stream.acceptWaveform(samples, 16_000)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
    }

    override val text: String get() = recognizer.getResult(stream).text

    override val tokens: List<TimedToken>
        get() = recognizer.getResult(stream).let { r -> r.tokens.zip(r.timestamps.toList()).map { (t, s) -> TimedToken(t, s) } }

    override fun isEndpoint() = recognizer.isEndpoint(stream)

    override fun reset() = recognizer.reset(stream)

    /** A chunk and a half of quiet pushes the last words through the model. */
    override fun flush() = accept(FloatArray(24_000))

    override fun close() {
        stream.release()
        recognizer.release()
    }

    private companion object {
        const val PAUSE_AFTER_SPEECH_S = 0.8f
        const val MAX_LINE_S = 25f
    }
}
