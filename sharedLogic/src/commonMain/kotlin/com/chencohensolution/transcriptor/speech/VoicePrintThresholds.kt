package com.chencohensolution.transcriptor.speech

import com.chencohensolution.transcriptor.models.ModelCatalog

/**
 * How alike two prints of one voice-print model must be to be one speaker: [sameSpeaker], the cosine for
 * [SpeakerTracker] on whole lines' prints (live labels without segmentation, and matching the speaker pass's speakers
 * to each other and to known voices); [live], the same on [LiveSpeakers]' prints of 1–2.5 s pieces, which are noisier,
 * so lower; and [clustering], the speaker pass's clustering distance (sherpa-onnx's fast clustering). Each model's prints spread
 * differently, so each has its own, set from an A/B on the owner's lectures (docs/design.md, Speakers).
 */
data class VoicePrintThresholds(val sameSpeaker: Float, val clustering: Float, val live: Float) {
    companion object {
        private val byModel = mapOf(
            // Live (segmentation): the replay of the three lectures through LiveSpeakers (build/speaker-ab/live_ab.log):
            // CAM++ at 0.3 made 3/3/4 speakers (2/2/3 with 5% of the talk or more), ERes2NetV2 at 0.45 2/3/3; the
            // WeSpeaker models' short prints spread more: ResNet293 at 0.15 made 3/2/4 (0.3: 10/5/19).
            ModelCatalog.voicePrints.id to VoicePrintThresholds(SpeakerTracker.THRESHOLD, 0.85f, 0.3f),
            // Live: the A/B's best tracker thresholds over the three lectures (0.6/0.45/0.55 for ERes2NetV2; 0.35/0.5/0.3
            // ResNet293, 0.35/0.45/0.3 ResNet100, both mean-normalized). Clustering: lecture B's sweep (2 speakers, as
            // labeled, at 1.0 for ERes2NetV2); the WeSpeaker models need 1.1 (lecture C: 6-7 speakers at 1.1, 10-13 at 1.0).
            ModelCatalog.voicePrintsEres2NetV2.id to VoicePrintThresholds(0.55f, 1.0f, 0.45f),
            ModelCatalog.voicePrintsResNet293.id to VoicePrintThresholds(0.4f, 1.1f, 0.15f),
            ModelCatalog.voicePrintsSimAmResNet100.id to VoicePrintThresholds(0.4f, 1.1f, 0.15f),
        )

        /** [modelId]'s thresholds; CAM++'s for a model without its own. */
        fun of(modelId: String): VoicePrintThresholds = byModel[modelId] ?: byModel.getValue(ModelCatalog.voicePrints.id)

        /**
         * Live speaker turns' threshold for [modelId]'s prints of the pieces [segmentationId]'s model cuts (null:
         * pyannote's), moved by the user's [more] (-1..1). Each segmentation model cuts differently (DiariZen's pieces
         * are cleaner and its prints spread wider than pyannote's), so each pair has its own: [LIVE].
         */
        fun live(modelId: String, segmentationId: String?, more: Float = 0f): Float =
            shift(LIVE[modelId to Turns.of(segmentationId)] ?: of(modelId).live, more)

        /** How far the cosines move at the slider's ends, and the clustering distance. */
        const val COSINE_RANGE = 0.15f
        const val CLUSTERING_RANGE = 0.25f

        /** A tuned cosine [threshold] moved by the user's [more] (-1..1). */
        fun shift(threshold: Float, more: Float) = (threshold + more.coerceIn(-1f, 1f) * COSINE_RANGE).coerceIn(0.05f, 0.95f)

        /**
         * [modelId]'s thresholds moved by the user's setting [more], -1 (fewer speakers: a voice joins a speaker more
         * readily) to 1 (more speakers), 0 the tuned values: the cosines up by [COSINE_RANGE] at 1, the clustering
         * distance down by [CLUSTERING_RANGE].
         */
        fun of(modelId: String, more: Float): VoicePrintThresholds {
            val t = of(modelId)
            val m = more.coerceIn(-1f, 1f)
            return VoicePrintThresholds(
                sameSpeaker = (t.sameSpeaker + m * COSINE_RANGE).coerceIn(0.05f, 0.95f),
                clustering = (t.clustering - m * CLUSTERING_RANGE).coerceIn(0.1f, 2f),
                live = (t.live + m * COSINE_RANGE).coerceIn(0.05f, 0.95f),
            )
        }
    }

    /** The segmentation models live speaker turns can run, by their thresholds. */
    enum class Turns {
        Pyannote, DiariZenBase, DiariZenLarge, DiariZenMeeting;

        companion object {
            /** [segmentationId]'s kind: DiariZen's by name (from the catalog or Hugging Face); anything else pyannote's. */
            fun of(segmentationId: String?): Turns {
                val id = segmentationId?.lowercase() ?: return Pyannote
                return when {
                    "diarizen" !in id -> Pyannote
                    "meeting" in id -> DiariZenMeeting
                    "large" in id -> DiariZenLarge
                    else -> DiariZenBase
                }
            }
        }
    }
}

/**
 * Live thresholds per (voice-print model, segmentation model), from replaying the owner's lectures through the live
 * path with each pair on the Mac (build/speaker-ab/live_ab3.py, live_ab3_table.md; docs/design.md, Speakers): per
 * pair, the highest threshold whose lines are at most 2 points more often wrong (labeled lectures A, B, C) than at the
 * pair's best, since voices merged into one speaker is the failure seen while recording. A pair missing here uses the
 * model's [VoicePrintThresholds.live].
 */
private val LIVE: Map<Pair<String, VoicePrintThresholds.Turns>, Float> = run {
    val cam = ModelCatalog.voicePrints.id
    val eres = ModelCatalog.voicePrintsEres2NetV2.id
    val r293 = ModelCatalog.voicePrintsResNet293.id
    val r100 = ModelCatalog.voicePrintsSimAmResNet100.id
    val p = VoicePrintThresholds.Turns.Pyannote
    val base = VoicePrintThresholds.Turns.DiariZenBase
    val large = VoicePrintThresholds.Turns.DiariZenLarge
    val meeting = VoicePrintThresholds.Turns.DiariZenMeeting
    mapOf(
        // Wrong lines at these: pyannote 13.7 / 17.2 / 17.6 / 13.5 %; DiariZen base 11.4 / 12.9 / 12.8 / 18.1 %;
        // large 13.5 / 17.6 / 19.5 / 19.8 %; meeting 12.3 / 15.7 / 18.2 / 17.5 % (CAM++ / ERes2NetV2 / ResNet293 / ResNet100).
        (cam to p) to 0.35f, (eres to p) to 0.5f, (r293 to p) to 0.2f, (r100 to p) to 0.1f,
        (cam to base) to 0.35f, (eres to base) to 0.4f, (r293 to base) to 0.1f, (r100 to base) to 0.15f,
        (cam to large) to 0.35f, (eres to large) to 0.55f, (r293 to large) to 0.25f, (r100 to large) to 0.25f,
        (cam to meeting) to 0.35f, (eres to meeting) to 0.5f, (r293 to meeting) to 0.3f, (r100 to meeting) to 0.15f,
    )
}
