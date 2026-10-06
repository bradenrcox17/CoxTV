package com.coxtv.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Same palette as the TV app. */
object CoxColors {
    val Bg = Color(0xFF0A0C10)
    val Panel = Color(0xFF141821)
    val PanelHi = Color(0xFF1F2532)
    val Accent = Color(0xFF2F80FF)
    val LogoBlue = Color(0xFF4DA6FF)   // "TV" in the wordmark (matches the app icon art)
    val AccentDim = Color(0xFF1B3E73)
    val Text = Color(0xFFF2F4F8)
    val TextDim = Color(0xFF9AA3B2)
    val Fav = Color(0xFFFFC94D)
    val Error = Color(0xFFFF6B6B)
}

@Composable
fun CoxMobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CoxColors.Accent,
            onPrimary = Color.White,
            primaryContainer = CoxColors.AccentDim,
            onPrimaryContainer = Color.White,
            secondaryContainer = CoxColors.AccentDim,
            onSecondaryContainer = Color.White,
            background = CoxColors.Bg,
            onBackground = CoxColors.Text,
            surface = CoxColors.Bg,
            onSurface = CoxColors.Text,
            surfaceVariant = CoxColors.PanelHi,
            onSurfaceVariant = CoxColors.TextDim,
            surfaceContainer = CoxColors.Panel,
            surfaceContainerHigh = CoxColors.PanelHi,
            surfaceContainerHighest = CoxColors.PanelHi,
            outline = CoxColors.TextDim,
            error = CoxColors.Error,
        ),
        content = content,
    )
}
