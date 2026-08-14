package com.tsfeith.habits.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// The container roles matter as much as the primaries: components like the FAB and Card
// default to them, so leaving them unset drops Material's stock purple into a green app
// and flattens every card into the background.
private val LightScheme =
    lightColorScheme(
        primary = Green40,
        onPrimary = Color.White,
        primaryContainer = Green90,
        onPrimaryContainer = Green10,
        secondary = Sand40,
        secondaryContainer = Sand80,
        tertiary = Clay40,
        background = Bone,
        onBackground = Ink,
        surface = Bone,
        onSurface = Ink,
        surfaceVariant = Green95,
        onSurfaceVariant = InkSoft,
        surfaceContainer = BoneCard,
        surfaceContainerLow = BoneCard,
        surfaceContainerHigh = Color(0xFFF1EFEA),
        outlineVariant = Color(0xFFE2DFD8),
    )

private val DarkScheme =
    darkColorScheme(
        primary = Green80,
        onPrimary = Green10,
        primaryContainer = Green30,
        onPrimaryContainer = Green90,
        secondary = Sand80,
        secondaryContainer = Sand40,
        tertiary = Clay80,
        background = Charcoal,
        onBackground = Chalk,
        surface = Charcoal,
        onSurface = Chalk,
        surfaceVariant = Color(0xFF272B24),
        onSurfaceVariant = ChalkSoft,
        surfaceContainer = CharcoalCard,
        surfaceContainerLow = Color(0xFF191C17),
        surfaceContainerHigh = Color(0xFF262A23),
        outlineVariant = Color(0xFF343830),
    )

val LocalMosaicColors = staticCompositionLocalOf { LightMosaicColors }

/** Mosaic palette for the current theme. Use as `MosaicTheme.colors`. */
object MosaicTheme {
    val colors: MosaicColors
        @Composable get() = LocalMosaicColors.current
}

@Composable
fun HabitTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val mosaic = if (darkTheme) DarkMosaicColors else LightMosaicColors
    CompositionLocalProvider(LocalMosaicColors provides mosaic) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = HabitTypography,
            content = content,
        )
    }
}
