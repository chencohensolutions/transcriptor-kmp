package com.chencohensolution.transcriptor.speech

import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * pyannote's segmentation 3.0 ([segmentation]) with voice prints ([voicePrints], on the NPU when it has an NPU build
 * and [libDir] is given) and clustering, on sherpa-onnx: pyannote 3.1's pipeline, runnable on the phone. [threshold]
 * is the clustering distance, per voice-print model ([VoicePrintThresholds]): for CAM++, on a test recording, 0.5 split
 * one voice into ten, 0.8–0.9 kept it whole and still told the TV's two voices apart.
 */
class SherpaDiarizer(private val segmentation: File, voicePrints: VoicePrintFiles, threshold: Float, libDir: String? = null) : Diarizer, AutoCloseable {
    private fun config(embedding: SpeakerEmbeddingExtractorConfig, threshold: Float) = OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(segmentation.path),
            numThreads = THREADS,
        ),
        embedding = embedding,
        clustering = FastClusteringConfig(numClusters = -1, threshold = threshold),
        minDurationOn = 0.3f,
        minDurationOff = 0.5f,
    )

    private val diarization: OfflineSpeakerDiarization

    /** The voice prints come from the NPU. */
    val onNpu: Boolean

    init {
        var npu: OfflineSpeakerDiarization? = null
        if (voicePrints.npu.isNotEmpty() && libDir != null) {
            npu = try {
                OfflineSpeakerDiarization(config = config(voicePrints.config(libDir), threshold))
            } catch (e: Throwable) {
                Log.w(SherpaVoiceEmbedder.TAG, "NPU voice prints for the speaker pass failed, on the CPU instead", e)
                null
            }
        }
        onNpu = npu != null
        diarization = npu ?: OfflineSpeakerDiarization(
            config = config(SpeakerEmbeddingExtractorConfig(model = voicePrints.onnx.path, numThreads = THREADS), threshold),
        )
    }

    override fun diarize(samples: FloatArray): List<Turn> =
        diarization.process(samples).map { Turn((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.speaker.toString()) }

    override fun close() = diarization.release()

    private companion object {
        const val THREADS = 4
    }
}
