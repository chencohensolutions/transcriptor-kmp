package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R

/**
 * Option c: the voice range now, 80 Hz to 8 kHz in [METER_BANDS] bars (both channels mixed), with the two channels'
 * levels as thin lines under it. Shows whether the sound is a voice (bumps in the middle), a hum (only the left bars)
 * or hiss (only the right ones).
 */
@Composable
fun SpectrumC(state: StereoMeterState, modifier: Modifier = Modifier) {
    val colors = LocalTranscriptorColors.current
    val fill = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier.width(280.dp)) {
            Canvas(Modifier.width(280.dp).height(56.dp)) {
                val step = size.width / METER_BANDS
                val bar = step * 0.7f
                state.bands.forEachIndexed { i, v ->
                    val h = (size.height * v).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(fill.copy(alpha = 0.35f + 0.65f * v), topLeft = Offset(i * step + (step - bar) / 2, size.height - h), size = Size(bar, h), cornerRadius = CornerRadius(bar / 3))
                }
            }
            Row(Modifier.width(280.dp).padding(top = 2.dp)) {
                Text("100", style = MaterialTheme.typography.labelSmall, color = colors.time)
                Text("1k", style = MaterialTheme.typography.labelSmall, color = colors.time, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(stringResource(R.string.lecture_meters_8k), style = MaterialTheme.typography.labelSmall, color = colors.time)
            }
            Canvas(Modifier.width(280.dp).padding(top = 6.dp).height(8.dp)) {
                val h = 2.dp.toPx()
                drawRoundRect(track, size = Size(size.width, h), cornerRadius = CornerRadius(h))
                drawRoundRect(fill, size = Size(size.width * state.now.left, h), cornerRadius = CornerRadius(h))
                drawRoundRect(track, topLeft = Offset(0f, size.height - h), size = Size(size.width, h), cornerRadius = CornerRadius(h))
                drawRoundRect(fill, topLeft = Offset(0f, size.height - h), size = Size(size.width * state.now.right, h), cornerRadius = CornerRadius(h))
            }
        }
    }
}
