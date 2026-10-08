package com.coxtv.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.coxtv.core.R

/** CoxTV brand colors: black and orange, one accent (same as the website and the TV apps). */
object CoxColors {
    val Bg = Color(0xFF0A0A0B)
    val Panel = Color(0xFF141416)
    val PanelHi = Color(0xFF1E1E21)
    val Accent = Color(0xFFFF8200)
    val OnAccent = Color(0xFF0A0A0B)      // text on orange
    val AccentDim = Color(0xFF3A2410)
    val Text = Color(0xFFEDEDED)
    val TextDim = Color(0xFFA3A3A8)
    val Fav = Accent
    val Error = Color(0xFFFF6B5E)
}

/** Geist and Geist Mono (OFL), bundled in the core module. */
object CoxFonts {
    val Sans = FontFamily(
        Font(R.font.geist_regular, FontWeight.Normal),
        Font(R.font.geist_medium, FontWeight.Medium),
        Font(R.font.geist_semibold, FontWeight.SemiBold),
        Font(R.font.geist_bold, FontWeight.Bold),
        Font(R.font.geist_black, FontWeight.ExtraBold),
        Font(R.font.geist_black, FontWeight.Black),
    )
    val Mono = FontFamily(Font(R.font.geist_mono_medium, FontWeight.Medium))
}

private fun TextStyle.geist() = copy(fontFamily = CoxFonts.Sans)

private val coxTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.geist(), displayMedium = t.displayMedium.geist(), displaySmall = t.displaySmall.geist(),
        headlineLarge = t.headlineLarge.geist().copy(fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.geist().copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.geist().copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.geist().copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.geist().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.geist().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.geist(), bodyMedium = t.bodyMedium.geist(), bodySmall = t.bodySmall.geist(),
        labelLarge = t.labelLarge.geist(), labelMedium = t.labelMedium.geist(), labelSmall = t.labelSmall.geist(),
    )
}

@Composable
fun CoxMobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CoxColors.Accent,
            onPrimary = CoxColors.OnAccent,
            primaryContainer = CoxColors.Accent,
            onPrimaryContainer = CoxColors.OnAccent,
            secondaryContainer = CoxColors.Accent,
            onSecondaryContainer = CoxColors.OnAccent,
            background = CoxColors.Bg,
            onBackground = CoxColors.Text,
            surface = CoxColors.Bg,
            onSurface = CoxColors.Text,
            surfaceVariant = CoxColors.PanelHi,
            onSurfaceVariant = CoxColors.TextDim,
            surfaceContainer = CoxColors.Panel,
            surfaceContainerHigh = CoxColors.PanelHi,
            surfaceContainerHighest = CoxColors.PanelHi,
            outline = Color(0xFF3A3A40),
            outlineVariant = Color(0xFF2A2A2E),
            error = CoxColors.Error,
        ),
        typography = coxTypography,
        content = content,
    )
}
