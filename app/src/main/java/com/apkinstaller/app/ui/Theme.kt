package com.apkinstaller.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.apkinstaller.app.data.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DD0B8),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF12453D),
    onPrimaryContainer = Color(0xFFB4F2E4),
    secondary = Color(0xFF9CCBC2),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE0E3E5),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE0E3E5),
    surfaceVariant = Color(0xFF1C2328),
    onSurfaceVariant = Color(0xFFBFC8CC),
    surfaceContainer = Color(0xFF182025),
    surfaceContainerHigh = Color(0xFF1E282E),
    errorContainer = Color(0xFF5C1F1F),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4F2E4),
    onPrimaryContainer = Color(0xFF00201A),
)

@Composable
fun ApkInstallerTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
