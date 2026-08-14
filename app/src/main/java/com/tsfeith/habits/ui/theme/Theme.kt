package com.tsfeith.habits.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// The container colours matter as much as the primaries: components like the FAB default
// to primaryContainer, so leaving them unset leaves Material's stock purple sitting in the
// middle of a green app.
private val LightScheme =
    lightColorScheme(
        primary = Green40,
        onPrimary = Color.White,
        primaryContainer = Green90,
        onPrimaryContainer = Green10,
        secondary = Sand40,
        tertiary = Clay40,
    )

private val DarkScheme =
    darkColorScheme(
        primary = Green80,
        onPrimary = Green10,
        primaryContainer = Green30,
        onPrimaryContainer = Green90,
        secondary = Sand80,
        tertiary = Clay80,
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
            typography = Typography(),
            content = content,
        )
    }
}
