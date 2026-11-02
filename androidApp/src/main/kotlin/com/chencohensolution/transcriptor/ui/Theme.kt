package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection

/** Colors Material's scheme has no role for: recording, notes, times. */
@Immutable
data class TranscriptorColors(
    val recording: Color,
    val note: Color,
    val onNote: Color,
    val time: Color,
    val partial: Color,
    /** One per speaker, in the order they first spoke (then again from the first). */
    val speakers: List<Color>,
)

private val lightExtra = TranscriptorColors(
    recording = Color(0xFFD93025),
    note = Color(0xFFFFF1D6),
    onNote = Color(0xFF4A3510),
    time = Color(0xFF7A7F99),
    partial = Color(0xFF9AA0B8),
    speakers = listOf(Color(0xFF1A73E8), Color(0xFF188038), Color(0xFFA142F4), Color(0xFFE37400), Color(0xFF007B83), Color(0xFFC5221F)),
)
private val darkExtra = TranscriptorColors(
    recording = Color(0xFFFF6B5E),
    note = Color(0xFF3B3220),
    onNote = Color(0xFFFFE2B0),
    time = Color(0xFF8E93AD),
    partial = Color(0xFF6C7190),
    speakers = listOf(Color(0xFF8AB4F8), Color(0xFF81C995), Color(0xFFD7AEFB), Color(0xFFFCAD70), Color(0xFF78D9EC), Color(0xFFF28B82)),
)

val LocalTranscriptorColors = staticCompositionLocalOf { lightExtra }

@Composable
fun TranscriptorTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (dark) {
        darkColorScheme(primary = Color(0xFFB8C3FF), onPrimary = Color(0xFF14205E), primaryContainer = Color(0xFF2E3A8C))
    } else {
        lightColorScheme(primary = Color(0xFF2E3A8C), onPrimary = Color.White, primaryContainer = Color(0xFFDDE1FF))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalTranscriptorColors provides if (dark) darkExtra else lightExtra) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Right to left for languages written that way: a notebook's content follows its language, not the phone's. */
fun layoutDirectionOf(language: String): LayoutDirection =
    if (language in setOf("he", "iw", "ar", "fa", "ur", "yi")) LayoutDirection.Rtl else LayoutDirection.Ltr

/** 12:03, or 1:02:03 past an hour. */
fun formatClock(millis: Long): String {
    val total = millis / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
