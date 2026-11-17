package com.chencohensolution.transcriptor.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/** The app's screen switch, for elements shared between screens (MainActivity's AnimatedContent). */
class SharedScopes(val shared: SharedTransitionScope, val animated: AnimatedVisibilityScope)

/** How long Record takes to become Stop, and the screens to cross-fade. */
const val MORPH_MILLIS = 700

/** Set by MainActivity around each screen; null in previews and the UI lab (no morph there). */
val LocalSharedScopes = compositionLocalOf<SharedScopes?> { null }

/**
 * The red Record button and the recording screen's red Stop: one element that morphs between the screens, its bounds
 * eased from the wide button to the round one and back (owner's request, Nov 17, 2026).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedRecordButton(): Modifier {
    val scopes = LocalSharedScopes.current ?: return this
    return with(scopes.shared) {
        this@sharedRecordButton.sharedBounds(
            rememberSharedContentState("record-button"), scopes.animated,
            // Eased, no spring (no bounce), slow enough to follow (owner's call, Nov 17, 2026).
            boundsTransform = { _, _ -> tween(MORPH_MILLIS, easing = FastOutSlowInEasing) },
            enter = androidx.compose.animation.fadeIn(tween(MORPH_MILLIS, easing = FastOutSlowInEasing)),
            exit = androidx.compose.animation.fadeOut(tween(MORPH_MILLIS, easing = FastOutSlowInEasing)),
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}
