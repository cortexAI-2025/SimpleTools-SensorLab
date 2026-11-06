package com.simpletools.sensorlab.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.simpletools.sensorlab.core.settings.ThemeMode

// SimpleTools family look: shared Material 3 neutral surfaces, one accent per product.
// SensorLab accent = teal (same tonal values as the SimpleTools template "teal" accent).
private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF006A6A), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF6FF7F6), onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF006A6A), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF6FF7F6), onSecondaryContainer = Color(0xFF002020),
)
private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF4CDADA), onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F4F), onPrimaryContainer = Color(0xFF6FF7F6),
    secondary = Color(0xFF4CDADA), onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF004F4F), onSecondaryContainer = Color(0xFF6FF7F6),
)

/** Series colours that stay distinguishable in light and dark mode (X, Y, Z). */
object ChartColors {
    val x = Color(0xFFE5484D)
    val y = Color(0xFF30A46C)
    val z = Color(0xFF3E63DD)
    val single = Color(0xFF12A594)
    val compareA = Color(0xFF3E63DD)
    val compareB = Color(0xFFF76B15)
}

@Composable
fun SensorLabTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
