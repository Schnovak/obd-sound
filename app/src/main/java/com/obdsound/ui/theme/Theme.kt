package com.obdsound.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = Orange,
    secondary = OrangeLight,
    background = DarkBackground,
    surface = DarkSurface,
    error = Red,
    onPrimary = DarkBackground,
    onBackground = GrayText,
    onSurface = GrayText,
)

@Composable
fun ObdSoundTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
