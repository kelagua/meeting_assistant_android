package com.codex.meetingassistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

@Composable
fun MeetingAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
