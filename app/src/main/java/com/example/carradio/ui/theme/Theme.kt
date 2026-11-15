package com.example.carradio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Match the neutral selection pattern in the Pleos system UI.
// Theme mode comes from Android; these are app-owned colors, not OEM resource IDs.
private val RadioLightColors = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0F0F0),
    onPrimaryContainer = Color(0xFF171717),
    inversePrimary = Color.White,
    secondary = Color.Black,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF171717),
    tertiary = Color.Black,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0F0F0),
    onTertiaryContainer = Color(0xFF171717),
    background = Color.White,
    onBackground = Color(0xFF171717),
    surface = Color.White,
    onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF666666),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF171717),
    inverseOnSurface = Color.White,
    outline = Color(0xFF808080),
    outlineVariant = Color(0xFFD4D4D4),
    scrim = Color.Black,
    surfaceDim = Color(0xFFE8E8E8),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFF0F0F0),
    surfaceContainerHighest = Color(0xFFE8E8E8),
)

private val RadioDarkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF282828),
    onPrimaryContainer = Color(0xFFF5F5F5),
    inversePrimary = Color.Black,
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF282828),
    onSecondaryContainer = Color(0xFFF5F5F5),
    tertiary = Color.White,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF282828),
    onTertiaryContainer = Color(0xFFF5F5F5),
    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF151515),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF282828),
    onSurfaceVariant = Color(0xFFB3B3B3),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFF5F5F5),
    inverseOnSurface = Color(0xFF151515),
    outline = Color(0xFF999999),
    outlineVariant = Color(0xFF454545),
    scrim = Color.Black,
    surfaceDim = Color(0xFF0D0D0D),
    surfaceBright = Color(0xFF383838),
    surfaceContainerLowest = Color(0xFF080808),
    surfaceContainerLow = Color(0xFF151515),
    surfaceContainer = Color(0xFF1C1C1C),
    surfaceContainerHigh = Color(0xFF242424),
    surfaceContainerHighest = Color(0xFF303030),
)

@Composable
fun CarRadioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) RadioDarkColors else RadioLightColors,
        typography = Typography,
        content = content,
    )
}
