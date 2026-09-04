package com.gasplanet.grabber.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Orange = Color(0xFFFF6B35)
private val OrangeDim = Color(0xFF7A2F14)
private val Slate = Color(0xFF121822)
private val SlateRaised = Color(0xFF1A2231)
private val SlateLine = Color(0xFF2A3648)

private val Dark = darkColorScheme(
    primary = Orange,
    onPrimary = Color.White,
    primaryContainer = OrangeDim,
    onPrimaryContainer = Color(0xFFFFDCCF),
    secondary = Color(0xFF7FB3FF),
    background = Slate,
    onBackground = Color(0xFFE6ECF5),
    surface = Slate,
    onSurface = Color(0xFFE6ECF5),
    surfaceVariant = SlateRaised,
    onSurfaceVariant = Color(0xFFA9B7CC),
    outline = SlateLine,
    error = Color(0xFFFF6B6B),
)

// The app is dark by choice -- it is used for video, often in the dark -- but
// a light scheme is defined so a phone forced to light mode is still legible.
private val Light = lightColorScheme(
    primary = Color(0xFFC2410C),
    onPrimary = Color.White,
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFECEFF4),
)

@Composable
fun GrabberTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = Typography(),
        content = content,
    )
}
