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
 * Option a: two level bars, one per channel, each with a peak that holds a moment. The fill turns to the error color
 * near the top, where the sound would clip. Left to right whatever the app's language: the channels are the file's.
 */
@Composable
fun StereoBarsA(level: StereoLevel, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChannelBar(stringResource(R.string.lecture_meters_left), level.left, level.leftPeak)
            ChannelBar(stringResource(R.string.lecture_meters_right), level.right, level.rightPeak)
        }
    }
}

@Composable
private fun ChannelBar(name: String, level: Float, peak: Float) {
    val colors = LocalTranscriptorColors.current
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    val hot = MaterialTheme.colorScheme.error
    val tick = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = colors.time, modifier = Modifier.width(16.dp))
        Canvas(Modifier.weight(1f).height(10.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            val w = size.width * level.coerceIn(0f, 1f)
            drawRoundRect(fill, size = Size(w, size.height), cornerRadius = r)
            if (level > HOT) drawRoundRect(hot, topLeft = Offset(size.width * HOT, 0f), size = Size(w - size.width * HOT, size.height), cornerRadius = r)
            val x = size.width * peak.coerceIn(0f, 1f)
            drawRect(if (peak > HOT) hot else tick, topLeft = Offset((x - 2.dp.toPx()).coerceAtLeast(0f), 0f), size = Size(2.dp.toPx(), size.height))
        }
    }
}

private const val HOT = 0.92f
