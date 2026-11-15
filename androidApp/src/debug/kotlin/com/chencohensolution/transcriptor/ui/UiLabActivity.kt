package com.chencohensolution.transcriptor.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
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
                    option != null && screen != null -> {
                        val leave = { optionId = null }
                        BackHandler(onBack = leave)
                        val at = screen.options.indexOf(option)
                        // The lab's own bar above the option: back to the options, and the one before and after, so
                        // options are compared without going through the list. The option's screen is drawn under
                        // it, and its own back arrow leaves the option too (LocalLabBack).
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.inverseSurface)) {
                            LabBar(
                                title = "${option.id} · ${at + 1}/${screen.options.size}",
                                onList = leave,
                                onPrevious = screen.options.getOrNull(at - 1)?.let { { optionId = it.id } },
                                onNext = screen.options.getOrNull(at + 1)?.let { { optionId = it.id } },
                            )
                            Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
                                CompositionLocalProvider(LocalLabBack provides leave) {
                                    // A new option starts fresh: nothing remembered from the one before.
                                    key(option.id) { option.content() }
                                }
                            }
                        }
                    }
                    screen != null -> {
                        BackHandler { screenId = null }
                        LabList(
                            title = screen.title,
                            rows = screen.options.map { LabRow("${it.id} · ${it.title}", it.note) },
                            onOpen = { optionId = screen.options[it].id },
                            onBack = { screenId = null },
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

/** Leaves the option that is open, back to its screen's options: what a lab option gives its screen's back arrow. */
internal val LocalLabBack = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * The bar over an open option, in the inverse colors so it never reads as part of the screen under it: the options
 * list, the option's id with its place among the screen's options, and the option before and after.
 */
@Composable
private fun LabBar(title: String, onList: () -> Unit, onPrevious: (() -> Unit)?, onNext: (() -> Unit)?) {
    val on = MaterialTheme.colorScheme.inverseOnSurface
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "‹ Options", color = on, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onList).padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Text(title, color = on, fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Text(
            "‹ Prev", color = on.copy(alpha = if (onPrevious != null) 1f else 0.35f), fontSize = 14.sp,
            modifier = Modifier.clickable(enabled = onPrevious != null) { onPrevious?.invoke() }.padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Text(
            "Next ›", color = on.copy(alpha = if (onNext != null) 1f else 0.35f), fontSize = 14.sp,
            modifier = Modifier.clickable(enabled = onNext != null) { onNext?.invoke() }.padding(horizontal = 12.dp, vertical = 10.dp),
        )
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
private fun LabList(title: String, rows: List<LabRow>, onOpen: (Int) -> Unit, onBack: (() -> Unit)? = null) {
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        if (onBack != null) item {
            Text(
                "‹ Screens", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
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
