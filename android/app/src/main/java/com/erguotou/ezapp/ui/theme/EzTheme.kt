package com.erguotou.ezapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Paper = Color(0xFF1D1E1B)
val Ink = Color(0xFFF3EEE4)
val MutedInk = Color(0xFFAAA79F)
val Vermilion = Color(0xFFF15A35)
val SignalGreen = Color(0xFF72C7A1)
val SignalYellow = Color(0xFFD9BC63)
val SignalOrange = Color(0xFFED8B4A)
val BridgeBlue = Color(0xFF79AEE8)
val SoftLine = Color(0xFF383934)
val Night = Color(0xFF171816)

private val Palette = darkColorScheme(
    primary = Vermilion,
    onPrimary = Color(0xFFFFFBF4),
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFF272824),
    onSurface = Ink,
    surfaceVariant = Color(0xFF30312C),
    onSurfaceVariant = MutedInk,
    outline = SoftLine,
    error = Color(0xFFFFB4AB),
)

@Composable
fun EzTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Palette, content = content)
}
