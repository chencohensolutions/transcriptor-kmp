package com.chencohensolution.transcriptor.speech

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/** Voice prints with a speaker-embedding model ([file], ONNX) on sherpa-onnx's extractor, on the CPU. */
class SherpaVoiceEmbedder(file: File) : VoiceEmbedder {
    private val extractor = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = file.path, numThreads = 1))

    override fun embed(samples: FloatArray): FloatArray {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, 16_000)
            stream.inputFinished()
            return extractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    override fun close() = extractor.release()
}
