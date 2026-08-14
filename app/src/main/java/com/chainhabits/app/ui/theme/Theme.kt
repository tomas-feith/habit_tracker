package com.chainhabits.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

private val LightScheme = lightColorScheme(
    primary = Green40,
    secondary = Sand40,
    tertiary = Clay40,
)

private val DarkScheme = darkColorScheme(
    primary = Green80,
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
