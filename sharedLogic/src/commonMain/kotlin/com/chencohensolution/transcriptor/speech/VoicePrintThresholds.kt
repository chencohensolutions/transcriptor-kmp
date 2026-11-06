package com.chencohensolution.transcriptor.speech

import com.chencohensolution.transcriptor.models.ModelCatalog

/**
 * How alike two prints of one voice-print model must be to be one speaker: [sameSpeaker], the cosine for
 * [SpeakerTracker] (live labels, and matching the speaker pass's speakers to each other and to known voices), and
 * [clustering], the speaker pass's clustering distance (sherpa-onnx's fast clustering). Each model's prints spread
 * differently, so each has its own, set from an A/B on the owner's lectures (docs/design.md, Speakers).
 */
data class VoicePrintThresholds(val sameSpeaker: Float, val clustering: Float) {
    companion object {
        private val byModel = mapOf(
            ModelCatalog.voicePrints.id to VoicePrintThresholds(SpeakerTracker.THRESHOLD, 0.85f),
            ModelCatalog.voicePrintsEres2NetV2.id to VoicePrintThresholds(0.55f, 0.75f),
            ModelCatalog.voicePrintsResNet293.id to VoicePrintThresholds(0.45f, 0.85f),
            ModelCatalog.voicePrintsSimAmResNet100.id to VoicePrintThresholds(0.45f, 0.85f),
        )

        /** [modelId]'s thresholds; CAM++'s for a model without its own. */
        fun of(modelId: String): VoicePrintThresholds = byModel[modelId] ?: byModel.getValue(ModelCatalog.voicePrints.id)
    }
}
