package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.models.SpeechModel
import com.chencohensolution.transcriptor.speech.ModelStatus

/** What a model on first run is for, shown under its name. */
enum class FirstRunJob { Live, Improve, Voices, Turns }

data class FirstRunModel(val model: SpeechModel, val job: FirstRunJob, val status: ModelStatus)

/**
 * First run's "Get your models": the defaults for [language] on this phone ([models]), their total size, and
 * whether their download was [started].
 */
data class FirstRunState(val language: String, val models: List<FirstRunModel>, val started: Boolean) {
    val totalBytes get() = models.sumOf { it.model.sizeBytes }
    val allReady get() = models.all { it.status == ModelStatus.Ready }
}

class FirstRunActions(val download: () -> Unit, val later: () -> Unit, val retry: (SpeechModel) -> Unit, val done: () -> Unit)

/**
 * The app's first screen on a new install: the models it will use, got with one tap through the same downloads as
 * Settings › Models (they go on in the background). Later opens the app without them; Settings › Models has them.
 */
@Composable
fun FirstRunScreen(state: FirstRunState, actions: FirstRunActions) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(32.dp))
                Text(stringResource(R.string.first_run_title), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.first_run_body, languageName(state.language)),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                state.models.forEach { ModelRow(it, actions) }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.first_run_total, modelsSize(state.totalBytes)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.started && !state.allReady) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.first_run_background), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                when {
                    !state.started -> {
                        TextButton(onClick = actions.later) { Text(stringResource(R.string.first_run_later)) }
                        Spacer(Modifier.size(8.dp))
                        Button(onClick = actions.download) { Text(stringResource(R.string.first_run_download)) }
                    }
                    state.allReady -> Button(onClick = actions.done) { Text(stringResource(R.string.first_run_start)) }
                    else -> TextButton(onClick = actions.done) { Text(stringResource(R.string.first_run_continue)) }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(item: FirstRunModel, actions: FirstRunActions) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    when (item.job) {
                        FirstRunJob.Live -> R.string.first_run_live
                        FirstRunJob.Improve -> R.string.first_run_improve
                        FirstRunJob.Voices -> R.string.first_run_voices
                        FirstRunJob.Turns -> R.string.first_run_turns
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "${isolated(item.model.name.replace(" · ", " · "))} · ${modelsSize(item.model.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (val status = item.status) {
            ModelStatus.Ready -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            is ModelStatus.Downloading -> CircularProgressIndicator(progress = { status.progress }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            ModelStatus.Checking -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            ModelStatus.WaitingForWifi -> Text(stringResource(R.string.model_waiting_wifi), style = MaterialTheme.typography.labelSmall)
            is ModelStatus.Failed -> TextButton(onClick = { actions.retry(item.model) }) {
                Text(stringResource(R.string.models_retry), color = MaterialTheme.colorScheme.error)
            }
            ModelStatus.Missing -> {}
        }
    }
}
