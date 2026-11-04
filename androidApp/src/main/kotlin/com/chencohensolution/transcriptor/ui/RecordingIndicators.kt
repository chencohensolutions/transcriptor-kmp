package com.chencohensolution.transcriptor.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** A red dot that breathes while recording, and stays still when paused. */
@Composable
fun RecordingDot(paused: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalTranscriptorColors.current
    val pulse by rememberInfiniteTransition(label = "dot").animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha",
    )
    Box(modifier.size(10.dp).alpha(if (paused) 1f else pulse).clip(CircleShape).background(if (paused) colors.time else colors.recording))
}

/** Five bars that follow the microphone's level. */
@Composable
fun LevelBars(level: Float, modifier: Modifier = Modifier, height: Int = 18) {
    val colors = LocalTranscriptorColors.current
    Row(modifier.height(height.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(0.5f, 0.8f, 1f, 0.75f, 0.45f).forEach { weight ->
            Box(
                Modifier.width(3.dp).fillMaxHeight((0.15f + level * weight).coerceAtMost(1f))
                    .clip(RoundedCornerShape(2.dp)).background(colors.recording.copy(alpha = 0.85f)),
            )
        }
    }
}
