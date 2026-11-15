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
 * Option b: the last 8 seconds as a strip that scrolls left, the left channel above the middle line and the right one
 * below it. Shows the rhythm of speech, pauses, and which side is louder; newest on the right.
 */
@Composable
fun StereoStripB(history: List<StereoLevel>, modifier: Modifier = Modifier) {
    val colors = LocalTranscriptorColors.current
    val left = MaterialTheme.colorScheme.primary
    val right = colors.speakers[1]
    val line = MaterialTheme.colorScheme.outlineVariant
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier.width(300.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(stringResource(R.string.lecture_meters_left), style = MaterialTheme.typography.labelMedium, color = left)
                Text(stringResource(R.string.lecture_meters_right), style = MaterialTheme.typography.labelMedium, color = right)
            }
            Canvas(Modifier.weight(1f).height(64.dp)) {
                val mid = size.height / 2
                drawRect(line, topLeft = Offset(0f, mid - 0.5.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
                val step = size.width / METER_HISTORY
                val bar = (step * 0.6f).coerceAtLeast(1.dp.toPx())
                val start = METER_HISTORY - history.size
                history.forEachIndexed { i, s ->
                    val x = (start + i) * step
                    val up = (mid - 1.dp.toPx()) * s.left
                    val down = (mid - 1.dp.toPx()) * s.right
                    drawRoundRect(left, topLeft = Offset(x, mid - 1.dp.toPx() - up), size = Size(bar, up), cornerRadius = CornerRadius(bar / 2))
                    drawRoundRect(right, topLeft = Offset(x, mid + 1.dp.toPx()), size = Size(bar, down), cornerRadius = CornerRadius(bar / 2))
                }
            }
        }
    }
}
