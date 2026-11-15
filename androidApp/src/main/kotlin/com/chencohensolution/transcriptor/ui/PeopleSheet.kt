package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.notebook.Speaker
import com.chencohensolution.transcriptor.review.Person
import com.chencohensolution.transcriptor.review.ReviewState

/**
 * "Who was here?": every voice of the moment in one sheet, the one who talked most first. Each has a piece to play
 * (their longest line), a name field that offers the voices the app knows, their share of the talking, and Merge
 * into…, for a voice that is really someone else's. A name someone here already has merges the two. Names are kept on
 * Done on the keyboard, on leaving the field, and when the sheet closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PeopleSheet(state: ReviewState, actions: ReviewActions, onDismiss: () -> Unit) {
    val people = state.people
    // What is typed and not kept yet, by speaker.
    val typed = remember { mutableStateMapOf<String, String>() }
    var focused by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    fun keep(id: String) {
        val name = typed.remove(id)?.trim() ?: return
        if (name.isNotEmpty() && name != people.firstOrNull { it.speakerId == id }?.name) actions.namePerson(id, name)
    }
    fun close() {
        typed.keys.toList().forEach(::keep)
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = ::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.people_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                pluralStringResource(R.plurals.people_summary, people.size, people.size, momentLength(state.lecture.durationMillis)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            if (people.isEmpty()) Text(stringResource(R.string.people_none), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
            people.forEach { p ->
                PersonRow(
                    p, people, state,
                    value = typed[p.speakerId] ?: p.name,
                    onValue = { typed[p.speakerId] = it },
                    onDone = { keep(p.speakerId); focus.clearFocus() },
                    onFocus = { has -> if (has) focused = p.speakerId else { if (focused == p.speakerId) focused = null; keep(p.speakerId) } },
                    onPlay = { actions.playPerson(p.speakerId) },
                    onMerge = { into -> typed.remove(p.speakerId); actions.mergeSpeakers(p.speakerId, into) },
                )
                // The voices the app knows, while this one's name is empty.
                val offered = state.knownNames.filter { name -> people.none { it.name == name } }.take(8)
                if (focused == p.speakerId && (typed[p.speakerId] ?: p.name).isEmpty() && offered.isNotEmpty()) {
                    LazyRow(Modifier.padding(start = 56.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(offered) { name ->
                            SuggestionChip(onClick = { typed.remove(p.speakerId); actions.namePerson(p.speakerId, name); focus.clearFocus() }, label = { Text(name, maxLines = 1) })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = ::close) { Text(stringResource(R.string.people_done)) }
            }
        }
    }
}

@Composable
private fun PersonRow(
    p: Person, people: List<Person>, state: ReviewState,
    value: String, onValue: (String) -> Unit, onDone: () -> Unit, onFocus: (Boolean) -> Unit,
    onPlay: () -> Unit, onMerge: (into: String) -> Unit,
) {
    val color = speakerColor(p.speakerId)
    val playing = state.playing && state.positionMillis in p.sampleStartMillis until p.sampleEndMillis
    var merging by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(
                onClick = onPlay, modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = color.copy(alpha = 0.18f), contentColor = color),
            ) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (playing) R.string.review_pause else R.string.people_play, speakerLabel(Speaker(p.speakerId, p.name))))
            }
            OutlinedTextField(
                value = value, onValueChange = onValue, singleLine = true,
                placeholder = { Text(speakerLabel(Speaker(p.speakerId, ""))) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).onFocusChanged { onFocus(it.isFocused) },
            )
            if (people.size > 1) Box {
                IconButton(onClick = { merging = true }) { Icon(Icons.AutoMirrored.Filled.CallMerge, stringResource(R.string.people_merge)) }
                DropdownMenu(expanded = merging, onDismissRequest = { merging = false }) {
                    Text(stringResource(R.string.people_merge_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    people.filter { it.speakerId != p.speakerId }.forEach { other ->
                        DropdownMenuItem(
                            text = { Text(isolated(speakerLabel(Speaker(other.speakerId, other.name)))) },
                            onClick = { merging = false; onMerge(other.speakerId) },
                        )
                    }
                }
            }
        }
        Row(Modifier.padding(start = 56.dp, top = 6.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { p.share }, color = color, trackColor = color.copy(alpha = 0.15f), drawStopIndicator = {},
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.people_share, (p.share * 100).toInt().coerceAtLeast(if (p.talkMillis > 0) 1 else 0)) + " · " + pluralStringResource(R.plurals.people_lines, p.lines, p.lines),
                style = MaterialTheme.typography.labelSmall, color = LocalTranscriptorColors.current.time,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        if (p.minor && people.size > 1) {
            Row(Modifier.padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.people_minor), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = { merging = true }) { Text(stringResource(R.string.people_merge)) }
            }
        }
    }
}
