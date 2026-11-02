package com.chencohensolution.transcriptor.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The UI lab (debug builds only): the app's screens and suggested designs for them, with made-up data
 * ([com.chencohensolution.transcriptor.lab.LabData]), without the microphone, the models or the files. The first
 * page lists the screens ([labScreens]); a screen lists its options, the first being what the app has now (or, for a
 * screen the app doesn't have yet, the first suggestion). Taps that would reach a controller do nothing or are faked.
 * `./scripts/ui-lab.sh <screen> [option]` opens one directly. See docs/previews.md.
 */
class UiLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val askedScreen = intent.getStringExtra("screen")?.takeIf { id -> labScreens.any { it.id == id } }
        val askedOption = intent.getStringExtra("option")
        setContent {
            TranscriptorTheme {
                var screenId by rememberSaveable { mutableStateOf(askedScreen) }
                var optionId by rememberSaveable { mutableStateOf(askedOption) }
                val screen = labScreens.firstOrNull { it.id == screenId }
                val option = screen?.options?.firstOrNull { it.id == optionId }
                when {
                    option != null -> {
                        BackHandler { optionId = null }
                        option.content()
                    }
                    screen != null -> {
                        BackHandler { screenId = null }
                        LabList(
                            title = screen.title,
                            rows = screen.options.map { LabRow("${it.id} · ${it.title}", it.note) },
                            onOpen = { optionId = screen.options[it].id },
                        )
                    }
                    else -> LabList(
                        title = "UI lab",
                        rows = labScreens.map { LabRow(it.title, optionCount(it.options)) },
                        onOpen = {
                            screenId = labScreens[it].id
                            optionId = null
                        },
                    )
                }
            }
        }
    }
}

/** A screen in the lab. [options] starts with the screen as the app has it (when it does), then the suggestions. */
internal class LabScreen(val id: String, val title: String, val options: List<LabOption>)

/** One way to draw a screen: the current one or a suggestion. [id] is what `ui-lab.sh` takes. */
internal class LabOption(val id: String, val title: String, val note: String = "", val content: @Composable () -> Unit)

private class LabRow(val title: String, val note: String)

private fun optionCount(options: List<LabOption>): String {
    val suggestions = options.count { it.id != "current" }
    val head = if (options.any { it.id == "current" }) "Current + " else "Not in the app yet · "
    return head + "$suggestions suggestion${if (suggestions == 1) "" else "s"}"
}

@Composable
private fun LabList(title: String, rows: List<LabRow>, onOpen: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        item {
            Text(
                title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        itemsIndexed(rows) { i, row ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(i) }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(row.title, color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp)
                if (row.note.isNotEmpty()) Text(row.note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }
}
