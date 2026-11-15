package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.recording.METER_BANDS
import com.chencohensolution.transcriptor.recording.METER_HISTORY
import com.chencohensolution.transcriptor.recording.StereoLevel
import com.chencohensolution.transcriptor.recording.StereoMeterState
import com.chencohensolution.transcriptor.settings.LevelMeterStyle

// The sound level meters of the recording glance, one per LevelMeterStyle (Settings › While recording). They are laid
// out left to right whatever the app's language: the channels are the file's. A mono recording (both channels the
// same, StereoMeterState.stereo false) shows one channel. UI lab round: docs/ux-history/lecture/2026-11-15-stereo-meters.md.

/** The glance's meter, in [style]. */
@Composable
fun LevelMeter(meter: StereoMeterState, style: LevelMeterStyle, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        when (style) {
            LevelMeterStyle.Bars -> StereoLevelBars(meter, modifier, height = 30)
            LevelMeterStyle.Lines -> LevelLines(meter, modifier)
            LevelMeterStyle.Strip -> LevelStrip(meter, modifier)
            LevelMeterStyle.Spectrum -> VoiceSpectrum(meter, modifier)
            LevelMeterStyle.Spectrogram -> VoiceSpectrogram(meter, modifier)
        }
    }
}

/** Today's five bars, a set per channel when the recording is stereo; small enough for the top bar. */
@Composable
fun StereoLevelBars(meter: StereoMeterState, modifier: Modifier = Modifier, height: Int = 18) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        if (!meter.stereo) LevelBars(meter.now.level, modifier, height)
        else Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            LevelBars(meter.now.left, height = height)
            Spacer(Modifier.width((height / 3).coerceAtLeast(4).dp))
            LevelBars(meter.now.right, height = height)
        }
    }
}

/** A level bar per channel, each with a peak that holds a moment; the fill turns to the error color where it would clip. */
@Composable
private fun LevelLines(meter: StereoMeterState, modifier: Modifier) {
    val now = meter.now
    Column(modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (meter.stereo) {
            ChannelBar(stringResource(R.string.lecture_meter_left), now.left, now.leftPeak)
            ChannelBar(stringResource(R.string.lecture_meter_right), now.right, now.rightPeak)
        } else ChannelBar(null, now.left, now.leftPeak)
    }
}

@Composable
private fun ChannelBar(name: String?, level: Float, peak: Float) {
    val colors = LocalTranscriptorColors.current
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    val hot = MaterialTheme.colorScheme.error
    val tick = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (name != null) Text(name, style = MaterialTheme.typography.labelMedium, color = colors.time, modifier = Modifier.width(16.dp))
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

/** The last 8 seconds scrolling left: the left channel above the middle line, the right one below; newest on the right. */
@Composable
private fun LevelStrip(meter: StereoMeterState, modifier: Modifier) {
    val colors = LocalTranscriptorColors.current
    val left = MaterialTheme.colorScheme.primary
    val right = if (meter.stereo) colors.speakers[1] else left
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(modifier.width(300.dp), verticalAlignment = Alignment.CenterVertically) {
        if (meter.stereo) Column(Modifier.padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.lecture_meter_left), style = MaterialTheme.typography.labelMedium, color = left)
            Text(stringResource(R.string.lecture_meter_right), style = MaterialTheme.typography.labelMedium, color = right)
        }
        Canvas(Modifier.weight(1f).height(64.dp)) {
            val mid = size.height / 2
            val gap = 1.dp.toPx()
            drawRect(line, topLeft = Offset(0f, mid - gap / 2), size = Size(size.width, gap))
            val step = size.width / METER_HISTORY
            val bar = (step * 0.6f).coerceAtLeast(1.dp.toPx())
            val start = METER_HISTORY - meter.history.size
            meter.history.forEachIndexed { i, s ->
                val x = (start + i) * step
                val up = (mid - gap) * s.left
                val down = (mid - gap) * (if (meter.stereo) s.right else s.left)
                drawRoundRect(left, topLeft = Offset(x, mid - gap - up), size = Size(bar, up), cornerRadius = CornerRadius(bar / 2))
                drawRoundRect(right, topLeft = Offset(x, mid + gap), size = Size(bar, down), cornerRadius = CornerRadius(bar / 2))
            }
        }
    }
}

/**
 * The voice range now, 80 Hz to 8 kHz in [METER_BANDS] bars (both channels mixed), with the channels' levels as thin
 * lines under it: a voice is bumps in the middle, a hum the left bars, hiss the right ones.
 */
@Composable
private fun VoiceSpectrum(meter: StereoMeterState, modifier: Modifier) {
    val colors = LocalTranscriptorColors.current
    val fill = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier.width(280.dp)) {
        Canvas(Modifier.width(280.dp).height(56.dp)) {
            val step = size.width / METER_BANDS
            val bar = step * 0.7f
            for (i in 0 until METER_BANDS) {
                val v = meter.bands.getOrElse(i) { 0f }
                val h = (size.height * v).coerceAtLeast(2.dp.toPx())
                drawRoundRect(fill.copy(alpha = 0.35f + 0.65f * v), topLeft = Offset(i * step + (step - bar) / 2, size.height - h), size = Size(bar, h), cornerRadius = CornerRadius(bar / 3))
            }
        }
        Row(Modifier.width(280.dp).padding(top = 2.dp)) {
            Text(stringResource(R.string.lecture_meter_100), style = MaterialTheme.typography.labelSmall, color = colors.time)
            Text(stringResource(R.string.lecture_meter_1k), style = MaterialTheme.typography.labelSmall, color = colors.time, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text(stringResource(R.string.lecture_meter_8k), style = MaterialTheme.typography.labelSmall, color = colors.time)
        }
        Canvas(Modifier.width(280.dp).padding(top = 6.dp).height(if (meter.stereo) 8.dp else 2.dp)) {
            val h = 2.dp.toPx()
            fun line(y: Float, v: Float) {
                drawRoundRect(track, topLeft = Offset(0f, y), size = Size(size.width, h), cornerRadius = CornerRadius(h))
                drawRoundRect(fill, topLeft = Offset(0f, y), size = Size(size.width * v, h), cornerRadius = CornerRadius(h))
            }
            line(0f, meter.now.left)
            if (meter.stereo) line(size.height - h, meter.now.right)
        }
    }
}

/**
 * The last 8 seconds of the voice range as a spectrogram (low at the bottom, 8 kHz at the top), each band's loudness as
 * the strength of the color; the channels' levels as short bars beside it. Words, pauses, pitch and "s" sounds over time.
 */
@Composable
private fun VoiceSpectrogram(meter: StereoMeterState, modifier: Modifier) {
    val colors = LocalTranscriptorColors.current
    val ink = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(modifier.width(300.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).height(64.dp)) {
            drawRoundRect(track, cornerRadius = CornerRadius(6.dp.toPx()))
            val cw = size.width / METER_HISTORY
            val ch = size.height / METER_BANDS
            val start = METER_HISTORY - meter.bandHistory.size
            meter.bandHistory.forEachIndexed { t, bands ->
                bands.forEachIndexed { b, v ->
                    val a = ((v - 0.1f) / 0.9f).coerceIn(0f, 1f)
                    if (a > 0.02f) drawRect(ink.copy(alpha = a), topLeft = Offset((start + t) * cw, size.height - (b + 1) * ch), size = Size(cw + 0.5f, ch + 0.5f))
                }
            }
        }
        Column(Modifier.padding(start = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.height(48.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Level(meter.now.left, ink, track)
                if (meter.stereo) Level(meter.now.right, ink, track)
            }
            if (meter.stereo) Text(stringResource(R.string.lecture_meter_left_right), style = MaterialTheme.typography.labelSmall, color = colors.time)
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

/** For previews and the lab: a [StereoLevel]'s meter with nothing else. */
internal fun StereoLevel.asMeter(stereo: Boolean = true) = StereoMeterState(now = this, history = listOf(this), stereo = stereo)
