package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.semantics.Role
import com.chencohensolution.transcriptor.recording.MicInput
import com.chencohensolution.transcriptor.settings.LevelMeterStyle
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.recording.MicDetail

/** A phone's size in meters when it doesn't say (the S25's: 70.5 × 146.9 mm). */
private const val PHONE_W = 0.0705f
private const val PHONE_H = 0.1469f

/** Where a microphone is, as a fraction of the phone's width and height (y from the bottom), and whether on the back. */
private data class Spot(val x: Float, val y: Float, val back: Boolean)

/** [detail]'s position, else a guess from its place's name. */
private fun spotOf(address: String, detail: MicDetail?, depth: Float): Spot? {
    val x = detail?.x
    val y = detail?.y
    if (x != null && y != null) {
        // z runs from the back (0) to the front (the screen): nearer the back than the front counts as on the back.
        val z = detail.z
        val back = z != null && depth > 0f && z < depth / 2
        return Spot(x / PHONE_W, y / PHONE_H, back)
    }
    return when (address) {
        "bottom" -> Spot(0.5f, 0.02f, false)
        "top", "front" -> Spot(0.5f, 0.98f, false)
        // Seen from the front, the back cameras (top left from behind) are top right.
        "back" -> Spot(0.72f, 0.88f, true)
        else -> null
    }
}

/**
 * A drawing of the phone from the front with its own microphones on it: filled on its edges and front, a ring for one
 * on the back (by the cameras); the [picked] one in the accent color. From the positions the phone gives
 * ([MicDetail]), else from where each one's name says it is.
 */
@Composable
internal fun PhoneMicMap(addresses: List<String>, details: List<MicDetail>, picked: String?, level: Float = 0f) {
    val depth = details.mapNotNull { it.z }.maxOrNull() ?: 0f
    val spots = addresses.mapNotNull { a -> spotOf(a, details.firstOrNull { it.address == a }, depth)?.let { a to it } }
    if (spots.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val outline = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val label = MaterialTheme.typography.labelSmall
    val names = addresses.associateWith { micShortName(it) }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.width(220.dp).height(240.dp)) {
            val h = size.height * 0.92f
            val w = h * PHONE_W / PHONE_H
            val left = (size.width - w) / 2
            val top = (size.height - h) / 2
            drawRoundRect(outline, Offset(left, top), Size(w, h), CornerRadius(w * 0.12f), style = Stroke(2.dp.toPx()))
            // The front camera's hole at the top, to tell top from bottom.
            drawCircle(outline, w * 0.04f, Offset(left + w * 0.5f, top + h * 0.035f))
            for ((address, s) in spots) {
                val at = Offset(left + w * s.x.coerceIn(0f, 1f), top + h * (1 - s.y.coerceIn(0f, 1f)))
                val color = if (address == picked) accent else ink
                // The picked one glows with what it hears (scratch near it and it grows).
                if (address == picked && level > 0.02f) drawCircle(accent.copy(alpha = 0.25f), (6 + 22 * level).dp.toPx(), at)
                if (s.back) drawCircle(color, 6.dp.toPx(), at, style = Stroke(2.dp.toPx())) else drawCircle(color, 6.dp.toPx(), at)
                val text = measurer.measure(names[address].orEmpty(), label.copy(color = color))
                // The name outside the phone, on the side the dot is nearer (the right for one in the middle).
                val x = if (s.x < 0.4f) left - text.size.width - 8.dp.toPx() else left + w + 8.dp.toPx()
                drawText(text, topLeft = Offset(x.coerceIn(0f, size.width - text.size.width), (at.y - text.size.height / 2).coerceIn(0f, size.height - text.size.height)))
            }
        }
        Text(stringResource(R.string.settings_mic_map_legend), style = MaterialTheme.typography.labelSmall, color = ink)
    }
}

@Composable
private fun micShortName(address: String) = when (address) {
    "bottom" -> stringResource(R.string.settings_mic_short_bottom)
    "back" -> stringResource(R.string.settings_mic_short_back)
    "top", "front" -> stringResource(R.string.settings_mic_short_top)
    else -> address
}

/** What a phone microphone in [address] is for, and what the phone says about how it hears. */
@Composable
internal fun micPlaceNote(address: String, detail: MicDetail?): String {
    val place = when (address) {
        "bottom" -> stringResource(R.string.settings_mic_note_bottom)
        "back" -> stringResource(R.string.settings_mic_note_back)
        "top", "front" -> stringResource(R.string.settings_mic_note_top)
        else -> stringResource(R.string.settings_mic_builtin)
    }
    val extras = listOfNotNull(
        detail?.directionality?.let {
            stringResource(when (it) {
                "omni" -> R.string.settings_mic_omni
                "cardioid", "hypercardioid", "supercardioid" -> R.string.settings_mic_cardioid
                else -> R.string.settings_mic_bidirectional
            })
        },
        detail?.maxSplDb?.let { stringResource(R.string.settings_mic_max_spl, it.toInt()) },
    )
    return (listOf(place) + extras).joinToString(" · ")
}

/**
 * Settings › Recording format › Microphone, a page of its own: the drawing of the phone's microphones, a live level
 * meter of the picked one (both channels; scratch near a microphone and see which one hears), and the choices: the
 * phone's own in stereo, each of its microphones alone, and the Bluetooth, wired and USB ones connected or picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MicrophonePage(state: ModelsState, actions: ModelsActions, back: () -> Unit) {
    val picked = state.micInput
    val connected = picked == null || state.micInputs.any { it.key == picked.key }
    // Listens while the page is open, to whichever microphone is picked.
    DisposableEffect(picked?.key) {
        actions.startMicTest(picked)
        onDispose { actions.stopMicTest() }
    }
    BackHandler(onBack = back)
    val choices = buildList {
        add(null)
        if (picked != null && !connected) add(picked)
        addAll(state.micInputs)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.settings_mic)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).selectableGroup()) {
            val builtIn = state.micInputs.filter { it.kind == MicInput.Kind.BuiltIn }.map { it.name }
            if (builtIn.isNotEmpty()) PhoneMicMap(
                builtIn, state.micDetails, picked?.takeIf { it.kind == MicInput.Kind.BuiltIn }?.name, state.micMeter.now.level,
            )
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(stringResource(R.string.settings_mic_test), style = MaterialTheme.typography.bodyLarge)
                if (state.micTestOff) {
                    Text(stringResource(R.string.settings_mic_test_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LevelMeter(state.micMeter.copy(stereo = true), LevelMeterStyle.Lines, Modifier.padding(vertical = 8.dp))
                    if (state.micMeter.clipping) Text(stringResource(R.string.glance_clipping), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.settings_mic_test_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            choices.forEach { input ->
                val selected = input?.key == picked?.key
                Row(
                    Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton) { actions.setMicInput(input) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(if (input == null) stringResource(R.string.settings_mic_phone) else micName(input.name, input.kind), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when {
                                input == null -> stringResource(R.string.settings_mic_phone_note)
                                state.micInputs.none { it.key == input.key } -> stringResource(R.string.settings_mic_not_connected)
                                input.kind == MicInput.Kind.BuiltIn -> micPlaceNote(input.name, state.micDetails.firstOrNull { it.address == input.name })
                                else -> stringResource(micKind(input.kind))
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (state.micInputs.none { it.kind != MicInput.Kind.BuiltIn }) Text(
                stringResource(R.string.settings_mic_none), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}
