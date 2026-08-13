package com.example.myapplication.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val AppColorScheme = lightColorScheme(
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
        colorScheme = AppColorScheme,
        typography = Typography,
        content = content
    )
}
