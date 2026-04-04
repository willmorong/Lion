package com.willmorong.lion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = EmberWarm,
    onPrimary = Night,
    secondary = Dune,
    onSecondary = Night,
    tertiary = SuccessGlow,
    background = Night,
    onBackground = Dawn,
    surface = Color(0xFF20170F),
    onSurface = Dawn,
    surfaceVariant = Color(0xFF332518),
    onSurfaceVariant = Ash,
    outline = Color(0xFF6A5643),
    primaryContainer = Color(0xFF573114),
    error = ErrorRust,
    errorContainer = Color(0xFF5A2214),
)

private val LightColorScheme = lightColorScheme(
    primary = Ember,
    onPrimary = Dawn,
    secondary = Moss,
    onSecondary = Dawn,
    tertiary = SuccessGlow,
    background = Dawn,
    onBackground = Bark,
    surface = SunlitSand,
    onSurface = Bark,
    surfaceVariant = Dune,
    onSurfaceVariant = Clay,
    outline = Color(0xFFB59C7E),
    primaryContainer = Color(0xFFF3D6B4),
    error = ErrorRust,
    errorContainer = Color(0xFFF9D5CC),
)

@Composable
fun LionTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
