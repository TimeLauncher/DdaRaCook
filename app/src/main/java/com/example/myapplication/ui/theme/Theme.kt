package com.example.myapplication.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = Flame,
    secondary = Herb,
    tertiary = Ash,
    background = Ink,
    surface = Pan,
    onPrimary = Ink,
    onSecondary = Ink,
    onTertiary = Flour,
    onBackground = Flour,
    onSurface = Flour
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
