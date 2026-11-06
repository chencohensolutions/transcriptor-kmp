package com.chencohensolution.transcriptor.speech

import android.util.Log
import com.k2fsa.sherpa.onnx.QnnConfig
import com.k2fsa.sherpa.onnx.SpeakerSegmentation
import java.io.File

/**
 * A segmentation model ([model], ONNX with pyannote's metadata: pyannote's segmentation 3.0, or DiariZen) on
 * sherpa-onnx, one window at a time (10 s, DiariZen's 8 or 16 s), for live speaker turns: on the Qualcomm NPU when its
 * NPU build ([npu], compiled for that window) is given and loads, else on the CPU with [threads].
 */
class SherpaSegmenter(model: File, npu: File? = null, libDir: String? = null, threads: Int = 1) : Segmenter {
    private val segmentation: SpeakerSegmentation

    /** It runs on the NPU. */
    val onNpu: Boolean

    init {
        var onNpu: SpeakerSegmentation? = null
        if (npu != null && npu.exists() && libDir != null) {
            onNpu = try {
                SherpaNpuWhisperEngine.useNpuLibraries(libDir)
                SpeakerSegmentation(
                    model.path, 1,
                    QnnConfig(backendLib = "$libDir/libQnnHtp.so", contextBinary = npu.path, systemLib = "$libDir/libQnnSystem.so"),
                )
            } catch (e: Throwable) {
                Log.w(SherpaVoiceEmbedder.TAG, "segmentation on the NPU failed, on the CPU instead", e)
                null
            }
        }
        this.onNpu = onNpu != null
        segmentation = onNpu ?: SpeakerSegmentation(model.path, threads)
        Log.i(SherpaVoiceEmbedder.TAG, "segmentation on the ${if (this.onNpu) "NPU" else "CPU"}")
    }

    override val windowSize get() = segmentation.windowSize

    override fun segment(window: FloatArray) = Segmentation(
        segmentation.frameShift, segmentation.frameSize,
        powersetToActive(segmentation.forward(window), segmentation.numClasses, segmentation.numSpeakers, segmentation.powersetMaxClasses),
    )

    override fun close() = segmentation.release()

    companion object {
        /** The NPU build in the segmentation model's folder (one 10 s window, SM8750). */
        const val NPU_FILE = "window.bin"
    }
}
