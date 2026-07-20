package com.example.litereader.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightBg = Color(0xFFF7F7F7)
private val DarkBg = Color(0xFF121212)
private val SepiaBg = Color(0xFFF4ECD8)
private val SepiaText = Color(0xFF3E2723)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2B5C8F),
    background = LightBg,
    surface = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8BB2F9),
    background = DarkBg,
    surface = Color(0xFF1E1E1E)
)

private val SepiaColorScheme = lightColorScheme(
    primary = Color(0xFF8D6E63),
    background = SepiaBg,
    surface = SepiaBg,
    onBackground = SepiaText,
    onSurface = SepiaText
)

@Composable
fun LiteReaderTheme(
    themeMode: Int = 2,
    content: @Composable () -> Unit
) {
    val colors = when (themeMode) {
        0 -> LightColorScheme
        1 -> SepiaColorScheme
        else -> DarkColorScheme
    }

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
