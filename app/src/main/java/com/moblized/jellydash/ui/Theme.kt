package com.moblized.jellydash.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object Brand {
    val purple = Color(0xFFAA5CC3)
    val blue = Color(0xFF00A4DC)
    val gradient = Brush.linearGradient(listOf(purple, blue))
    val good = Color(0xFF2E9E6A)
    val warn = Color(0xFFD9822B)
}

private val Light = lightColorScheme(
    primary = Color(0xFF6B3FA0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBDDF7),
    onPrimaryContainer = Color(0xFF2A1046),
    secondary = Color(0xFF0079A8),
    onSecondary = Color.White,
    background = Color(0xFFF6F3FB),
    onBackground = Color(0xFF1B1440),
    surface = Color.White,
    onSurface = Color(0xFF1B1440),
    surfaceVariant = Color(0xFFEDE8F5),
    onSurfaceVariant = Color(0xFF5A5470),
    outline = Color(0xFFCBC3DA),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC79BE0),
    onPrimary = Color(0xFF2A1046),
    primaryContainer = Color(0xFF3F2763),
    onPrimaryContainer = Color(0xFFEBDDF7),
    secondary = Color(0xFF4CC4EE),
    onSecondary = Color(0xFF00344A),
    background = Color(0xFF120D2B),
    onBackground = Color(0xFFECE7F7),
    surface = Color(0xFF1D1740),
    onSurface = Color(0xFFECE7F7),
    surfaceVariant = Color(0xFF2A2352),
    onSurfaceVariant = Color(0xFFB9B1CF),
    outline = Color(0xFF453D6B),
    error = Color(0xFFF2B8B5),
)

@Composable
fun JellyTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
