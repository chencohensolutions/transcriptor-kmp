package com.chencohensolution.transcriptor.speech

import android.util.Log
import com.chencohensolution.transcriptor.recording.ModelInUse
import com.k2fsa.sherpa.onnx.QnnConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * Voice prints with a speaker-embedding model on sherpa-onnx's extractor: on the CPU ([VoicePrintFiles.onnx]), or on
 * the Qualcomm NPU when the model's folder has its NPU build ([VoicePrintFiles.npu]). If the NPU fails to load, the
 * same model runs on the CPU (its prints match the NPU's), and the failure is logged.
 */
class SherpaVoiceEmbedder(files: VoicePrintFiles, libDir: String? = null) : VoiceEmbedder {
    constructor(file: File) : this(VoicePrintFiles(file, emptyList()))

    private val extractor: SpeakerEmbeddingExtractor

    /** The prints come from the NPU. */
    val onNpu: Boolean

    /** The model has an NPU build for this phone, but it failed to load: the prints come from the CPU. */
    val npuFailed: Boolean

    init {
        var npu: SpeakerEmbeddingExtractor? = null
        if (files.npu.isNotEmpty() && libDir != null) {
            npu = try {
                SpeakerEmbeddingExtractor(config = files.config(libDir))
            } catch (e: Throwable) {
                Log.w(TAG, "NPU voice prints failed (${files.npu.first().parent}), on the CPU instead", e)
                null
            }
        }
        onNpu = npu != null
        npuFailed = files.npu.isNotEmpty() && libDir != null && npu == null
        extractor = npu ?: SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = files.onnx.path, numThreads = 1))
        Log.i(TAG, "voice prints with ${files.onnx.parentFile?.name} on the ${if (onNpu) "NPU" else "CPU"}")
    }

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

    /** Where the prints are made, for the lecture screen. */
    val runs get() = when {
        onNpu -> ModelInUse.Runs.Npu
        npuFailed -> ModelInUse.Runs.CpuAfterNpuFailed
        else -> ModelInUse.Runs.Cpu
    }

    companion object {
        const val TAG = "VoicePrints"
    }
}

/**
 * A voice-print model's files: its ONNX for the CPU, and its NPU build's context binaries (one per fixed length,
 * `t150.bin`, `t300.bin`, `t600.bin`), empty when it has none for this phone.
 */
data class VoicePrintFiles(val onnx: File, val npu: List<File>) {
    /** sherpa-onnx's extractor on the NPU, with Qualcomm's runtime from [libDir]. */
    fun config(libDir: String): SpeakerEmbeddingExtractorConfig {
        SherpaNpuWhisperEngine.useNpuLibraries(libDir)
        return SpeakerEmbeddingExtractorConfig(
            model = "",
            numThreads = 1,
            provider = "qnn",
            qnnConfig = QnnConfig(
                backendLib = "$libDir/libQnnHtp.so",
                contextBinary = npu.joinToString(",") { it.path },
                systemLib = "$libDir/libQnnSystem.so",
            ),
        )
    }

    companion object {
        /** The NPU builds' graphs: 1.5, 3 and 6 s of audio. */
        val NPU_GRAPHS = listOf("t150.bin", "t300.bin", "t600.bin")

        /** [dir]'s files: `model.onnx`, and the NPU graphs present when [npuChip] is the phone's chip (SM8750). */
        fun of(dir: File, chip: String?, npuChip: String = "SM8750") = VoicePrintFiles(
            File(dir, "model.onnx"),
            if (chip == npuChip) NPU_GRAPHS.map { File(dir, it) }.filter { it.exists() } else emptyList(),
        )
    }
}
