package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.location.MomentPath
import kotlin.math.cos

/**
 * A moment's path as a line drawing, north up, with no map (nothing leaves the phone): the part after the audio ended
 * lighter, the start a dot. Tapping near a point seeks the audio there.
 */
@Composable
fun PathDrawing(path: MomentPath, seek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val points = path.points.filter { it.accuracyM <= 100f }
    if (points.size < 2) return
    val line = MaterialTheme.colorScheme.primary
    val after = MaterialTheme.colorScheme.outline
    val audioEnd = path.audioEndMillis ?: Long.MAX_VALUE
    // An equirectangular projection around the middle latitude: right for a few kilometres.
    val midLat = (points.minOf { it.point.lat } + points.maxOf { it.point.lat }) / 2
    val k = cos(Math.toRadians(midLat))
    val xs = points.map { it.point.lon * k }
    val ys = points.map { it.point.lat }
    val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
    val span = maxOf(maxX - minX, maxY - minY, 1e-6)
    fun place(i: Int, w: Float, h: Float, pad: Float): Offset {
        val s = (minOf(w, h) - 2 * pad) / span
        val cx = (w - (maxX - minX) * s) / 2
        val cy = (h - (maxY - minY) * s) / 2
        return Offset((cx + (xs[i] - minX) * s).toFloat(), (h - cy - (ys[i] - minY) * s).toFloat())
    }
    Canvas(
        modifier.fillMaxWidth().aspectRatio(1.6f).pointerInput(path) {
            detectTapGestures { tap ->
                val pad = 12.dp.toPx()
                val nearest = points.indices.minBy { (place(it, size.width.toFloat(), size.height.toFloat(), pad) - tap).getDistance() }
                seek(points[nearest].atMillis.coerceAtMost(audioEnd))
            }
        },
    ) {
        val pad = 12.dp.toPx()
        fun pathOf(indices: List<Int>) = Path().apply {
            indices.forEachIndexed { n, i -> place(i, size.width, size.height, pad).let { if (n == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
        }
        val split = points.indexOfFirst { it.atMillis > audioEnd }.let { if (it < 0) points.size else it }
        val during = pathOf((0 until split).toList())
        // After the audio: from the last point during it, so the two lines meet.
        val later = pathOf(((split - 1).coerceAtLeast(0) until points.size).toList().takeIf { split < points.size }.orEmpty())
        drawPath(during, line, style = Stroke(3.dp.toPx()))
        drawPath(later, after, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        drawCircle(line, 5.dp.toPx(), place(0, size.width, size.height, pad))
    }
}

/** Speed through the moment (km/h), with the top speed; tapping the graph seeks the audio to that time. */
@Composable
fun SpeedGraph(path: MomentPath, seek: (Long) -> Unit, label: String, modifier: Modifier = Modifier) {
    val points = path.points.filter { it.speedMps != null && it.accuracyM <= 30f }
    if (points.size < 2) return
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val audioEnd = path.audioEndMillis
    val start = points.first().atMillis
    val end = maxOf(points.last().atMillis, start + 1)
    val top = maxOf(points.maxOf { it.speedMps!! } * 3.6f, 10f)
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        Canvas(
            Modifier.fillMaxWidth().height(96.dp).pointerInput(path) {
                detectTapGestures { tap -> seek((start + (tap.x / size.width) * (end - start)).toLong().coerceAtMost(audioEnd ?: Long.MAX_VALUE)) }
            },
        ) {
            fun x(t: Long) = (t - start).toFloat() / (end - start) * size.width
            fun y(v: Float) = size.height - v * 3.6f / top * size.height
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height))
            audioEnd?.takeIf { it in start..end }?.let { drawLine(grid, Offset(x(it), 0f), Offset(x(it), size.height), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
            val p = Path()
            points.forEachIndexed { i, pt -> if (i == 0) p.moveTo(x(pt.atMillis), y(pt.speedMps!!)) else p.lineTo(x(pt.atMillis), y(pt.speedMps!!)) }
            drawPath(p, line, style = Stroke(2.dp.toPx()))
        }
    }
}
