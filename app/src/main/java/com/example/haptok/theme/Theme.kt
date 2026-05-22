package com.example.haptok.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val HaptokDarkColorScheme = darkColorScheme(
    primary = HaptokViolet,
    onPrimary = TextPrimary,
    primaryContainer = HaptokViolet.copy(alpha = 0.3f),
    onPrimaryContainer = HaptokPurple,
    secondary = HaptokBlue,
    onSecondary = TextPrimary,
    secondaryContainer = HaptokBlue.copy(alpha = 0.3f),
    onSecondaryContainer = HaptokCyan,
    tertiary = HaptokCyan,
    onTertiary = TextPrimary,
    tertiaryContainer = HaptokCyan.copy(alpha = 0.3f),
    onTertiaryContainer = HaptokCyan,
    error = StatusError,
    onError = TextPrimary,
    errorContainer = StatusError.copy(alpha = 0.3f),
    onErrorContainer = StatusError,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = GlassBorder,
    outlineVariant = GlassWhite,
    inverseSurface = TextPrimary,
    inverseOnSurface = DarkBackground,
    inversePrimary = HaptokViolet,
    surfaceTint = HaptokViolet,
)

@Composable
fun HaptokTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = HaptokDarkColorScheme,
        typography = HaptokTypography,
        content = content,
    )
}
