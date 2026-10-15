package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = EmeraldGreen,
    onPrimary = Color.Black,
    primaryContainer = EmeraldGreenContainer,
    onPrimaryContainer = EmeraldGreenLight,
    secondary = CyanStatus,
    onSecondary = Color.Black,
    secondaryContainer = CardItemBgDark,
    onSecondaryContainer = Color.White,
    tertiary = AmberWarning,
    background = BgDark,
    onBackground = TextPrimary,
    surface = CardDark,
    onSurface = TextPrimary,
    surfaceVariant = CardItemBgDark,
    onSurfaceVariant = TextSecondary,
    error = RoseAlert,
    onError = Color.White
)

@Composable
fun FocusShieldTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}


