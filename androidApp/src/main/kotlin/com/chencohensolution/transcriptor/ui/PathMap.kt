package com.chencohensolution.transcriptor.ui

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.location.MomentPath
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** The map's style: OpenFreeMap's (OpenStreetMap data, free, no key). Only the map's area is fetched, never the path. */
private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

/**
 * The moment's path on a map, full screen (Moment info › Path › Show on map): the part while recording in the app's
 * blue, the part after the audio stopped dashed in grey, the start a dot. Tapping near the path seeks the audio there
 * and closes the map. The map's tiles come from the internet only while this is open.
 */
@Composable
fun PathMapDialog(path: MomentPath, seek: (Long) -> Unit, onDismiss: () -> Unit) {
    val points = remember(path) { path.points.filter { it.accuracyM <= 100f } }
    val audioEnd = path.audioEndMillis ?: Long.MAX_VALUE
    val split = points.indexOfFirst { it.atMillis > audioEnd }.let { if (it < 0) points.size else it }
    val during = points.take(split)
    val after = if (split < points.size) points.drop((split - 1).coerceAtLeast(0)) else emptyList()
    val blue = MaterialTheme.colorScheme.primary.let { Color.argb((it.alpha * 255).toInt(), (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val context = LocalContext.current
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val map = remember {
                MapLibre.getInstance(context)
                MapView(context).apply { onCreate(null) }
            }
            DisposableEffect(lifecycle, map) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> map.onStart()
                        Lifecycle.Event.ON_RESUME -> map.onResume()
                        Lifecycle.Event.ON_PAUSE -> map.onPause()
                        Lifecycle.Event.ON_STOP -> map.onStop()
                        else -> Unit
                    }
                }
                lifecycle.addObserver(observer)
                map.onStart(); map.onResume()
                onDispose {
                    lifecycle.removeObserver(observer)
                    map.onPause(); map.onStop(); map.onDestroy()
                }
            }
            AndroidView(factory = {
                map.getMapAsync { m ->
                    m.setStyle(Style.Builder().fromUri(MAP_STYLE)) { style ->
                        fun line(list: List<com.chencohensolution.transcriptor.location.PathPoint>) =
                            LineString.fromLngLats(list.map { Point.fromLngLat(it.point.lon, it.point.lat) })
                        if (during.size >= 2) {
                            style.addSource(GeoJsonSource("during", line(during)))
                            style.addLayer(LineLayer("during", "during").withProperties(
                                PropertyFactory.lineColor(blue), PropertyFactory.lineWidth(5f),
                                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            ))
                        }
                        if (after.size >= 2) {
                            style.addSource(GeoJsonSource("after", line(after)))
                            style.addLayer(LineLayer("after", "after").withProperties(
                                PropertyFactory.lineColor(Color.GRAY), PropertyFactory.lineWidth(4f), PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
                            ))
                        }
                        points.firstOrNull()?.let { s ->
                            style.addSource(GeoJsonSource("start", Point.fromLngLat(s.point.lon, s.point.lat)))
                            style.addLayer(CircleLayer("start", "start").withProperties(
                                PropertyFactory.circleColor(blue), PropertyFactory.circleRadius(7f),
                                PropertyFactory.circleStrokeColor(Color.WHITE), PropertyFactory.circleStrokeWidth(2f),
                            ))
                        }
                    }
                    if (points.size >= 2) {
                        val bounds = LatLngBounds.Builder().includes(points.map { LatLng(it.point.lat, it.point.lon) }).build()
                        m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 160))
                    } else points.firstOrNull()?.let { m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.point.lat, it.point.lon), 16.0)) }
                    // A tap near the path plays the audio from there.
                    m.addOnMapClickListener { tap ->
                        val here = m.projection.toScreenLocation(tap)
                        val nearest = points.minByOrNull {
                            val p = m.projection.toScreenLocation(LatLng(it.point.lat, it.point.lon))
                            (p.x - here.x) * (p.x - here.x) + (p.y - here.y) * (p.y - here.y)
                        }
                        val p = nearest?.let { m.projection.toScreenLocation(LatLng(it.point.lat, it.point.lon)) }
                        if (nearest != null && p != null && (p.x - here.x) * (p.x - here.x) + (p.y - here.y) * (p.y - here.y) < 60f * 60f * context.resources.displayMetrics.density) {
                            seek(nearest.atMillis.coerceAtMost(audioEnd))
                            onDismiss()
                            true
                        } else false
                    }
                }
                map
            }, modifier = Modifier.fillMaxSize())
            // The close button and the note at the top: MapLibre's credits (OpenStreetMap) stay visible at the bottom.
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)).statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.place_close)) }
                Text(stringResource(R.string.info_path_map_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, end = 8.dp))
            }
        }
    }
}
