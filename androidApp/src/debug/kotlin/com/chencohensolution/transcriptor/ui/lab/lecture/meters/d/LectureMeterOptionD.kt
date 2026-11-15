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
 * Option d: a spectrogram strip, the last 8 seconds of the voice range (low at the bottom, 8 kHz at the top) with each
 * band's loudness as the strength of the color; the two channels' levels as short bars on its right. Shows words,
 * pauses, pitch and "s" sounds over time.
 */
@Composable
fun SpectrogramD(state: StereoMeterState, modifier: Modifier = Modifier) {
    val colors = LocalTranscriptorColors.current
    val ink = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier.width(300.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.weight(1f).height(64.dp)) {
                drawRoundRect(track, cornerRadius = CornerRadius(6.dp.toPx()))
                val cw = size.width / METER_HISTORY
                val ch = size.height / METER_BANDS
                val start = METER_HISTORY - state.bandHistory.size
                state.bandHistory.forEachIndexed { t, bands ->
                    bands.forEachIndexed { b, v ->
                        val a = ((v - 0.1f) / 0.9f).coerceIn(0f, 1f)
                        if (a > 0.02f) drawRect(ink.copy(alpha = a), topLeft = Offset((start + t) * cw, size.height - (b + 1) * ch), size = Size(cw + 0.5f, ch + 0.5f))
                    }
                }
            }
            Column(Modifier.padding(start = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.height(48.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Level(state.now.left, ink, track)
                    Level(state.now.right, ink, track)
                }
                Text(stringResource(R.string.lecture_meters_left_right), style = MaterialTheme.typography.labelSmall, color = colors.time)
            }
        }
    }
}

@Composable
private fun Level(v: Float, fill: Color, track: Color) {
    Canvas(Modifier.width(6.dp).height(48.dp)) {
        val r = CornerRadius(size.width / 2)
        drawRoundRect(track, cornerRadius = r)
        val h = size.height * v
        drawRoundRect(fill, topLeft = Offset(0f, size.height - h), size = Size(size.width, h), cornerRadius = r)
    }
}
