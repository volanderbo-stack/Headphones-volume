package com.headphones.volume.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6EE7B7),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF14532D),
    onPrimaryContainer = Color(0xFFD1FAE5),
    error = Color(0xFFFCA5A5),
    background = Color(0xFF0E1113),
    onBackground = Color(0xFFE7ECEA),
    surface = Color(0xFF0E1113),
    onSurface = Color(0xFFE7ECEA),
    onSurfaceVariant = Color(0xFFA9B4B0),
    surfaceContainer = Color(0xFF181D20),
    surfaceContainerHigh = Color(0xFF20262A),
)

private val BaseTypography = Typography()

private val LargeTypography = BaseTypography.copy(
    bodyLarge = BaseTypography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontSize = 18.sp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = LargeTypography, content = content)
}
