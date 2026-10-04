package com.devcat.escootercontrol.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = SeedBlue40,
    onPrimary = Color.White,
    primaryContainer = IceBlue,
    onPrimaryContainer = InkBlue,
    secondary = SeedTeal40,
    onSecondary = Color.White,
    secondaryContainer = MistTeal,
    onSecondaryContainer = Color(0xFF0A332B),
    tertiary = Color(0xFF6C4EA0),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9DDFF),
    onTertiaryContainer = Color(0xFF24123F),
    background = SeedNeutral99,
    onBackground = Color(0xFF191B1F),
    surface = SeedNeutral99,
    onSurface = Color(0xFF191B1F),
    surfaceVariant = SoftGray,
    onSurfaceVariant = Color(0xFF45464D),
    outline = Color(0xFF76777F),
    error = DangerRed,
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB5C7FF),
    onPrimary = Color(0xFF16315E),
    primaryContainer = Color(0xFF2E4772),
    onPrimaryContainer = Color(0xFFD9E3FF),
    secondary = Color(0xFF94D6C7),
    onSecondary = Color(0xFF07372E),
    secondaryContainer = Color(0xFF245047),
    onSecondaryContainer = Color(0xFFB0F2E3),
    tertiary = Color(0xFFD3BCFF),
    onTertiary = Color(0xFF39265D),
    tertiaryContainer = Color(0xFF513D75),
    onTertiaryContainer = Color(0xFFEBDDFF),
    background = SeedNeutral10,
    onBackground = Color(0xFFE3E3E9),
    surface = SeedNeutral10,
    onSurface = Color(0xFFE3E3E9),
    surfaceVariant = Charcoal,
    onSurfaceVariant = Color(0xFFC6C6CF),
    outline = Color(0xFF8F9099),
    error = DangerRedDark,
    onError = Color(0xFF690005)
)

private val MidnightColors = darkColorScheme(
    primary = MidnightBlue,
    onPrimary = MidnightOnBlue,
    primaryContainer = MidnightBlueContainer,
    onPrimaryContainer = MidnightOnBlueContainer,
    secondary = MidnightCyan,
    onSecondary = Color(0xFF00363D),
    secondaryContainer = MidnightCyanContainer,
    onSecondaryContainer = Color(0xFFA8EDF7),
    tertiary = Color(0xFFD4BCFF),
    onTertiary = Color(0xFF3B275F),
    tertiaryContainer = Color(0xFF503C75),
    onTertiaryContainer = Color(0xFFEBDDFF),
    background = MidnightBackground,
    onBackground = MidnightText,
    surface = MidnightSurface,
    onSurface = MidnightText,
    surfaceVariant = MidnightSurfaceRaised,
    onSurfaceVariant = MidnightMuted,
    outline = MidnightOutline,
    error = DangerRedDark,
    onError = Color(0xFF690005)
)

enum class AppThemeMode { SYSTEM, LIGHT, DARK, MIDNIGHT }

@Composable
fun EscooterControlTheme(
    mode: AppThemeMode = AppThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK, AppThemeMode.MIDNIGHT -> true
    }

    val colorScheme = when {
        mode == AppThemeMode.MIDNIGHT -> MidnightColors
        dynamicColor && mode == AppThemeMode.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
