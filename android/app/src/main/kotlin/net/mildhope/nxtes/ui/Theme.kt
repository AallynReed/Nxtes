package net.mildhope.nxtes.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colours outside the Material scheme, per docs/ui.md. */
@Immutable
data class NxtesColors(
    val pageBackground: Color,
    val divider: Color,
    val accent: Color,
)

private val LightNxtes = NxtesColors(pageBackground = Color(0xFFF3F3F3), divider = Color(0xFFDDDDDD), accent = Color(0xFF4793EF))
private val DarkNxtes = NxtesColors(pageBackground = Color(0xFF000000), divider = Color(0xFF333333), accent = Color(0xFFF2B84B))

val LocalNxtesColors = staticCompositionLocalOf { LightNxtes }

private val LightScheme = lightColorScheme(
    primary = LightNxtes.accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EEFD),
    onPrimaryContainer = Color(0xFF0B3A73),
    secondaryContainer = Color(0xFFE3EEFD),
    onSecondaryContainer = Color(0xFF0B3A73),
    background = Color.White,
    surface = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainerHigh = Color(0xFFF3F3F3),
    onSurface = Color(0xFF222222),
    onSurfaceVariant = Color(0xFF666666),
    outline = Color(0xFFBBBBBB),
    outlineVariant = LightNxtes.divider,
)

private val DarkScheme = darkColorScheme(
    primary = DarkNxtes.accent,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A2E12),
    onPrimaryContainer = Color(0xFFFFE2A8),
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = DarkNxtes.accent,
    background = Color(0xFF111111),
    surface = Color(0xFF111111),
    surfaceContainer = Color(0xFF181818),
    surfaceContainerLow = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF000000),
    onSurface = Color(0xFFEEEEEE),
    onSurfaceVariant = Color(0xFFAAAAAA),
    outline = Color(0xFF555555),
    outlineVariant = DarkNxtes.divider,
)

@Composable
fun NxtesTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalNxtesColors provides if (dark) DarkNxtes else LightNxtes) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
