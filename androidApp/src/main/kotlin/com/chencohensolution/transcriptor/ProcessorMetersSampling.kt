package com.chencohensolution.transcriptor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.chencohensolution.transcriptor.recording.AndroidProcessorProbe
import com.chencohensolution.transcriptor.recording.ProcessorMeterSampler
import com.chencohensolution.transcriptor.recording.ProcessorMeters
import com.chencohensolution.transcriptor.settings.ProcessorMeterStyle
import com.chencohensolution.transcriptor.speech.npuBusy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The meters now (null until two readings make an interval) and the last minute of them. */
class MetersNow(val now: ProcessorMeters?, val history: List<ProcessorMeters>)

/**
 * The processor meters for the lecture screen: read about once a second, only while [style] isn't off and the screen
 * is started (not while the app is in the background or another screen shows). Reading again after a pause starts
 * afresh, so the gap isn't counted.
 */
@Composable
fun rememberProcessorMeters(style: ProcessorMeterStyle): MetersNow {
    var meters by remember { mutableStateOf(MetersNow(null, emptyList())) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sampler = remember { lazy { ProcessorMeterSampler(AndroidProcessorProbe { npuBusy.busyMillis }) } }
    LaunchedEffect(style) {
        meters = MetersNow(null, emptyList())
        if (style == ProcessorMeterStyle.Off) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val s = withContext(Dispatchers.IO) { sampler.value.also { it.reset() } }
            while (true) {
                val now = withContext(Dispatchers.IO) { s.sample() }
                if (s.history.isNotEmpty()) meters = MetersNow(now, s.history)
                delay(1_000)
            }
        }
    }
    return meters
}
