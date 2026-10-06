package com.coxtv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object CoxColors {
    val Bg = Color(0xFF0A0C10)
    val Panel = Color(0xFF141821)
    val PanelHi = Color(0xFF1F2532)
    val Accent = Color(0xFF2F80FF)
    val AccentDim = Color(0xFF1B3E73)
    val Text = Color(0xFFF2F4F8)
    val TextDim = Color(0xFF9AA3B2)
    val Fav = Color(0xFFFFC94D)
    val Error = Color(0xFFFF6B6B)
}

@Composable
fun CoxTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CoxColors.Accent,
            onPrimary = Color.White,
            background = CoxColors.Bg,
            onBackground = CoxColors.Text,
            surface = CoxColors.Panel,
            onSurface = CoxColors.Text,
            surfaceVariant = CoxColors.PanelHi,
            onSurfaceVariant = CoxColors.TextDim,
            error = CoxColors.Error,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides CoxColors.Text, content = content)
    }
}
