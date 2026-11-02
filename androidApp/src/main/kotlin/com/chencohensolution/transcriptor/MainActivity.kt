package com.chencohensolution.transcriptor

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.transcriptor.notebook.AUDIO_FLAC
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.Notebook
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.ui.LectureScreen
import com.chencohensolution.transcriptor.ui.LiveLectureActions
import com.chencohensolution.transcriptor.ui.TranscriptorTheme
import com.chencohensolution.transcriptor.ui.formatClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * For now: a start screen (the audio format, Start lecture, the last lecture), and the lecture while recording.
 * The start screen gives way to the Notebooks screen once its design is chosen in the UI lab, and the audio format
 * moves to Settings.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TranscriptorTheme {
                val live by Recording.session.state.collectAsStateWithLifecycle()
                val state = live
                if (state != null) {
                    LectureScreen(
                        state,
                        LiveLectureActions(
                            back = { moveTaskToBack(true) },
                            pauseOrResume = Recording.session::pauseOrResume,
                            stop = { RecordingService.stop(this) },
                            addNote = Recording.session::addNote,
                        ),
                    )
                } else {
                    StartScreen(onStart = ::startLecture)
                }
            }
        }
    }

    private fun startLecture() {
        val title = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())
        RecordingService.start(this, DEFAULT_NOTEBOOK.copy(title = getString(R.string.start_default_notebook)), title)
    }

    private companion object {
        /** Until notebooks exist: one notebook, in Hebrew. */
        val DEFAULT_NOTEBOOK = Notebook("default", "", "he", emptyList())
    }
}

@Composable
private fun StartScreen(onStart: () -> Unit) {
    var format by remember { mutableStateOf(Recording.settings.audioFormat) }
    val last by Recording.lastLecture.collectAsStateWithLifecycle()
    val compressing by Recording.compressing.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        val pending = withContext(Dispatchers.IO) { Recording.compression.pending() }
        if (pending.isNotEmpty() && compressing == null) RecordingService.compressPending(context)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) onStart()
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.start_format), style = MaterialTheme.typography.titleSmall)
            Column(Modifier.selectableGroup()) {
                listOf(
                    AudioFormat.Wav to R.string.format_wav,
                    AudioFormat.WavThenFlac to R.string.format_wav_then_flac,
                    AudioFormat.FlacLive to R.string.format_flac_live,
                ).forEach { (option, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = format == option, role = Role.RadioButton,
                            onClick = { format = option; Recording.settings.audioFormat = option },
                        ).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = format == option, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Button(
                onClick = { permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.start_lecture)) }
            last?.let { LastLecture(it, compressing) }
            Text(stringResource(R.string.main_coming_soon), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LastLecture(lecture: Lecture, compressing: Float?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.start_last, lecture.title, formatClock(lecture.durationMillis), lecture.notes.size, lecture.audioFile),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (compressing != null && lecture.audioFile != AUDIO_FLAC) {
            Text(stringResource(R.string.compressing_flac), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { compressing }, modifier = Modifier.fillMaxWidth())
        }
    }
}
