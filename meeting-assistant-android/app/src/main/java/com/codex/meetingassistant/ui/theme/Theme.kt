package com.codex.meetingassistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F6E5B),
    onPrimary = Color(0xFFF6FFFB),
    primaryContainer = Color(0xFFD7F7EA),
    onPrimaryContainer = Color(0xFF04251F),
    secondary = Color(0xFF305C74),
    surface = Color(0xFFF5F3EE),
    surfaceContainer = Color(0xFFE8E4DC),
    surfaceContainerLow = Color(0xFFF1EEE8),
    surfaceContainerHigh = Color(0xFFE2DDD4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CE2C1),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005241),
    onPrimaryContainer = Color(0xFFD7F7EA),
    secondary = Color(0xFFA9CCE0),
    surface = Color(0xFF11171B),
    surfaceContainer = Color(0xFF1B242A),
    surfaceContainerLow = Color(0xFF162026),
    surfaceContainerHigh = Color(0xFF223039),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun MeetingAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val cappedDensity = remember(density) {
        Density(
            density = density.density,
            fontScale = density.fontScale.coerceAtMost(1.12f),
        )
    }

    CompositionLocalProvider(LocalDensity provides cappedDensity) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}
