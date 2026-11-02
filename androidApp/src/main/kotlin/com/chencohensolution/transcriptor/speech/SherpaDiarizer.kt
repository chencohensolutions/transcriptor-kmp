package com.chencohensolution.transcriptor.speech

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * pyannote's segmentation 3.0 ([segmentation]) with voice prints ([voicePrints], CAM++) and clustering, on
 * sherpa-onnx: pyannote 3.1's pipeline, runnable on the phone. [THRESHOLD] is the clustering distance: on a test
 * recording, 0.5 split one voice into ten, 0.8–0.9 kept it whole and still told the TV's two voices apart.
 */
class SherpaDiarizer(segmentation: File, voicePrints: File) : Diarizer, AutoCloseable {
    private val diarization = OfflineSpeakerDiarization(
        config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(segmentation.path),
                numThreads = THREADS,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(model = voicePrints.path, numThreads = THREADS),
            clustering = FastClusteringConfig(numClusters = -1, threshold = THRESHOLD),
            minDurationOn = 0.3f,
            minDurationOff = 0.5f,
        ),
    )

    override fun diarize(samples: FloatArray): List<Turn> =
        diarization.process(samples).map { Turn((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.speaker.toString()) }

    override fun close() = diarization.release()

    private companion object {
        const val THRESHOLD = 0.85f
        const val THREADS = 4
    }
}
