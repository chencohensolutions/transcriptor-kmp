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
            // Live: the A/B's best tracker thresholds over the three lectures (0.6/0.45/0.55 for ERes2NetV2; 0.35/0.5/0.3
            // ResNet293, 0.35/0.45/0.3 ResNet100, both mean-normalized). Clustering: lecture B's sweep (2 speakers, as
            // labeled, at 1.0 for ERes2NetV2; the WeSpeaker models still split at 0.7, provisional until the sweep ends).
            ModelCatalog.voicePrintsEres2NetV2.id to VoicePrintThresholds(0.55f, 1.0f),
            ModelCatalog.voicePrintsResNet293.id to VoicePrintThresholds(0.4f, 1.0f),
            ModelCatalog.voicePrintsSimAmResNet100.id to VoicePrintThresholds(0.4f, 1.0f),
        )

        /** [modelId]'s thresholds; CAM++'s for a model without its own. */
        fun of(modelId: String): VoicePrintThresholds = byModel[modelId] ?: byModel.getValue(ModelCatalog.voicePrints.id)
    }
}
