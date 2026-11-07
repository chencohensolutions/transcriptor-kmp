package com.chencohensolution.transcriptor.recording

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.settings.ProcessorMeterStyle

/** The lecture screen while recording, as [LectureSession] produces it. */
data class LiveLectureState(
    val notebookTitle: String,
    val lecture: Lecture,
    /** The line live transcription is writing now, before its end; null in a pause between lines, or without it. */
    val partial: String?,
    /** The position in the audio: what a note typed now is pinned to. */
    val elapsedMillis: Long,
    /** The microphone's level, 0..1, for the meter. */
    val level: Float,
    val paused: Boolean,
    /** The live model; null when none is downloaded for the lecture's language. */
    val liveModelName: String?,
    /** Live transcription is this far behind the audio (it catches up in pauses); null without it. */
    val lagMillis: Long?,
    val liveLoading: Boolean = false,
    /** Live transcription stopped working; the recording goes on. */
    val liveError: String? = null,
    /** Stop was pressed: the recording has ended and the last lines are being transcribed. */
    val finishing: Boolean = false,
    /** The voice-print model labeling the speakers, and where it runs; null without speaker labels. */
    val voicePrints: ModelInUse? = null,
    /** The speaker pass runs after Stop, with pyannote's segmentation and [voicePrints]' model. */
    val speakerPassOnStop: Boolean = false,
    /** Speakers are found live with segmentation ([com.chencohensolution.transcriptor.speech.LiveSpeakers]). */
    val speakersLive: Boolean = false,
    /** How the processors' load is shown (Settings › Advanced); [ProcessorMeterStyle.Off] hides it. */
    val meterStyle: ProcessorMeterStyle = ProcessorMeterStyle.Off,
    /** How hard the processors are working (refreshed about every second); null until the first reading. */
    val meters: ProcessorMeters? = null,
    /** The last minute of [meters], oldest first, for [ProcessorMeterStyle.Graphs]. */
    val meterHistory: List<ProcessorMeters> = emptyList(),
)

/** A model a recording uses ([name], for the screen) and where it runs: unknown until it loads. */
data class ModelInUse(val name: String, val runs: Runs = Runs.NotYet) {
    enum class Runs {
        /** It loads with the first line long enough for it. */
        NotYet,
        Npu,
        Cpu,

        /** Its NPU build failed to load: the same model on the CPU. */
        CpuAfterNpuFailed,
    }
}
