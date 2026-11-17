package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.recording.LiveLectureState
import kotlinx.coroutines.delay

/**
 * The moment while recording, at a glance: for a phone lying on the table in a conversation. A large clock, whether it
 * is transcribing now or after, who is talking and their last words (when live), and three large controls: Pause,
 * **Mark** (a note without words at this second, when typing isn't possible) and Stop. The model lines are not here:
 * only a problem is said (live transcription stopped, a model that didn't fit). The transcript, fixing lines and
 * typed notes are one tap away ([onTranscript]).
 */
@Composable
internal fun RecordingGlance(state: LiveLectureState, actions: LiveLectureActions, onTranscript: () -> Unit) {
    val colors = LocalTranscriptorColors.current
    // "Marked at 12:20", for a moment after the tap.
    var marked by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(marked) {
        if (marked != null) {
            delay(2_500)
            marked = null
        }
    }
    val notes = state.lecture.notes.size
    val stop = rememberArmedStop(state.stopTwice, actions.stop)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.lecture_back)) }
            Column(Modifier.weight(1f)) {
                Text(state.lecture.title, style = MaterialTheme.typography.titleMedium.copy(textDirection = titleDirection(state.lecture.language)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.labelNames.isNotEmpty()) Text(state.labelNames, style = MaterialTheme.typography.bodySmall, color = colors.time, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                formatClock(state.elapsedMillis), fontSize = 76.sp, fontWeight = FontWeight.SemiBold,
                color = if (state.paused) colors.time else MaterialTheme.colorScheme.onBackground,
            )
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RecordingDot(state.paused)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        when {
                            state.finishing -> R.string.lecture_finishing
                            state.paused -> R.string.lecture_paused
                            state.recordOnly && !state.transcribeAfter -> R.string.glance_recording_no_transcript
                            state.recordOnly -> R.string.glance_recording_only
                            else -> R.string.glance_recording_live
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
            // Live transcription off and on while recording, when a live model is on the phone for the language.
            if (state.canTranscribe && !state.finishing) {
                androidx.compose.material3.FilterChip(
                    selected = state.transcribing, onClick = { actions.setTranscribing(!state.transcribing) },
                    label = { Text(stringResource(if (state.transcribing) R.string.glance_live_on else R.string.glance_live_off)) },
                    leadingIcon = { Icon(if (state.transcribing) Icons.Filled.Subtitles else Icons.Filled.SubtitlesOff, null, Modifier.size(18.dp)) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            LevelMeter(state.stereoMeter, state.levelMeter, Modifier.padding(top = 14.dp))
            // Which microphone, when it isn't the phone's own; the one picked in Settings when it isn't recording.
            val input = state.input
            val mic = when {
                state.inputMissing != null -> stringResource(R.string.glance_mic_missing, micName(state.inputMissing.orEmpty(), null))
                input != null -> stringResource(R.string.glance_mic, micName(input.name, input.kind))
                else -> null
            }
            if (mic != null) Text(
                mic, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                color = if (state.inputMissing != null) MaterialTheme.colorScheme.error else colors.time,
            )
            // Too loud: a sample hit full scale in the last seconds (a concert up close).
            if (state.stereoMeter.clipping) Text(
                stringResource(R.string.glance_clipping), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp),
            )
            // A problem is said; while things work, the models aren't.
            val problem = when {
                state.liveError != null -> stringResource(R.string.glance_live_stopped)
                state.skippedForMemory != null && !state.improveOnStop -> stringResource(R.string.glance_model_skipped, isolated(state.skippedForMemory.orEmpty()))
                else -> null
            }
            if (problem != null) Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))

            // Who is talking, and their last words: the line being heard, else the last one finished.
            val last = state.lecture.segments.lastOrNull()
            val words = state.partial ?: last?.text
            if (!state.recordOnly && words != null) {
                val speaker = last?.speakerId?.let { id -> state.lecture.speakers.firstOrNull { it.id == id } }
                Column(
                    Modifier.padding(top = 28.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onTranscript)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (speaker != null) ReviewSpeakerChip(speaker)
                        val people = state.lecture.speakers.size
                        if (people > 1) Text(
                            pluralStringResource(R.plurals.glance_people, people, people), style = MaterialTheme.typography.labelMedium, color = colors.time,
                            modifier = Modifier.padding(start = if (speaker != null) 10.dp else 0.dp),
                        )
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides layoutDirectionOf(state.lecture.language)) {
                        Text(
                            words, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = if (state.partial != null) colors.partial else MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        Text(
            if (stop.armed) stringResource(R.string.lecture_stop_again)
            else marked?.let { stringResource(R.string.glance_marked, formatClock(it)) }
                ?: if (notes > 0) pluralStringResource(R.plurals.glance_notes, notes, notes) else stringResource(R.string.glance_mark_hint),
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = if (stop.armed) MaterialTheme.colorScheme.error else if (marked != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = actions.pauseOrResume, enabled = !state.finishing, modifier = Modifier.size(64.dp)) {
                Icon(
                    if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    stringResource(if (state.paused) R.string.lecture_resume else R.string.lecture_pause), Modifier.size(30.dp),
                )
            }
            Button(
                onClick = { marked = actions.mark() }, enabled = !state.finishing && !state.paused, shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp).height(72.dp),
            ) {
                Icon(Icons.Filled.Flag, null, Modifier.size(26.dp))
                Text(stringResource(R.string.glance_mark), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleLarge)
            }
            FilledIconButton(
                onClick = stop.tap, enabled = !state.finishing, modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.recording, contentColor = Color.White),
            ) { Icon(if (stop.armed) Icons.Filled.Check else Icons.Filled.Stop, stringResource(if (stop.armed) R.string.lecture_stop_again else R.string.lecture_stop), Modifier.size(30.dp)) }
        }
        TextButton(onClick = onTranscript, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp, bottom = 8.dp)) {
            Text(stringResource(if (state.recordOnly) R.string.glance_open_notes else R.string.glance_open_transcript))
        }
    }
}
