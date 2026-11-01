package com.scan.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.scan.app.Lang
import com.scan.app.LocalLang

/** SCAN palette -- deep charcoal with a cyan "scan" accent, matching the app artwork. */
object ScanColors {
    val Bg = Color(0xFF0A0E14)
    val Surface = Color(0xFF121821)
    val Surface2 = Color(0xFF1A222D)
    val Accent = Color(0xFF22D3EE)
    val AccentDim = Color(0xFF0E7490)
    val Text = Color(0xFFEAF2F8)
    val TextDim = Color(0xFF93A4B5)
    val Danger = Color(0xFFFF6B6B)
    val Warn = Color(0xFFFFC857)
    val Ok = Color(0xFF4ADE80)
    val Free = Color(0xFF4ADE80)
}

private val DarkColors = darkColorScheme(
    primary = ScanColors.Accent,
    onPrimary = Color(0xFF04202A),
    primaryContainer = ScanColors.AccentDim,
    onPrimaryContainer = Color(0xFFD9F8FF),
    secondary = ScanColors.Accent,
    onSecondary = Color(0xFF04202A),
    background = ScanColors.Bg,
    onBackground = ScanColors.Text,
    surface = ScanColors.Surface,
    onSurface = ScanColors.Text,
    surfaceVariant = ScanColors.Surface2,
    onSurfaceVariant = ScanColors.TextDim,
    error = ScanColors.Danger,
    onError = Color(0xFF2A0505),
    outline = Color(0xFF2A3542)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7490),
    onPrimary = Color.White,
    background = Color(0xFFF6F9FB),
    onBackground = Color(0xFF101820),
    surface = Color.White,
    onSurface = Color(0xFF101820),
    surfaceVariant = Color(0xFFE7EEF4),
    onSurfaceVariant = Color(0xFF4A5A68),
    error = Color(0xFFB3261E),
    outline = Color(0xFFC3D0DA)
)

val ChipShape = RoundedCornerShape(50)
val CardShape = RoundedCornerShape(20.dp)

@Composable
fun ScanTheme(
    lang: Lang,
    dark: Boolean = true,
    content: @Composable () -> Unit
) {
    val scheme = if (dark) DarkColors else LightColors
    CompositionLocalProvider(
        LocalLang provides lang,
        LocalLayoutDirection provides if (lang == Lang.AR) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Slight border used by every card in the app. */
@Composable
fun cardBorder(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

val LocalSnacker = staticCompositionLocalOf<((String) -> Unit)?> { null }
