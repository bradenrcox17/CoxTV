package com.coxtv.ui.components

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** True on phones/tablets (touchscreen), false on Fire TV. Used for hints and on-screen buttons. */
val LocalIsTouch = staticCompositionLocalOf { false }

/** The layout every screen was designed for (a 1080p TV). */
private const val DESIGN_WIDTH_DP = 960f
private const val DESIGN_HEIGHT_DP = 540f

/**
 * Scales the UI down on screens smaller than a TV (phones in landscape), so every screen keeps
 * the same layout instead of overflowing. On Fire TV the scale is exactly 1.
 */
@Composable
fun FitToScreen(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val touch = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) }
    Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.displayCutout)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val scale = minOf(1f, maxWidth.value / DESIGN_WIDTH_DP, maxHeight.value / DESIGN_HEIGHT_DP)
            CompositionLocalProvider(
                LocalDensity provides Density(density.density * scale, density.fontScale),
                LocalIsTouch provides touch,
            ) {
                // Shrink above the on-screen keyboard (after scaling, so the scale stays put)
                // so the field being typed in can scroll into view.
                Box(Modifier.fillMaxSize().imePadding()) { content() }
            }
        }
    }
}

/**
 * Makes a TV-Material clickable Surface respond to touch: TV Surfaces only react to the remote's
 * OK button. A tap focuses the item (so it highlights like on TV) and clicks it; a long press
 * runs [onLongClick]. Put it on the Surface's modifier.
 */
fun Modifier.touchClick(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier = composed {
    val focus = remember { FocusRequester() }
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val hasLong = onLongClick != null
    focusRequester(focus).pointerInput(hasLong) {
        detectTapGestures(
            onLongPress = if (hasLong) {
                {
                    runCatching { focus.requestFocus() }
                    longClick?.invoke()
                }
            } else null,
            onTap = {
                runCatching { focus.requestFocus() }
                click()
            },
        )
    }
}
