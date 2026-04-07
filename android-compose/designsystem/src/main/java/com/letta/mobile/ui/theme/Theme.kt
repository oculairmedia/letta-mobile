package com.letta.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkBackground,
    primaryContainer = DarkPrimaryVariant,
    onPrimaryContainer = DarkOnSurface,
    secondary = TealAccent,
    onSecondary = DarkBackground,
    secondaryContainer = DarkSurfaceContainer,
    onSecondaryContainer = DarkOnSurface,
    tertiary = CyanAccent,
    onTertiary = DarkBackground,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = Color(0xFF303030),
    scrim = Color(0xFF000000),
    inverseSurface = LightSurface,
    inverseOnSurface = LightOnSurface,
    inversePrimary = LightPrimary,
    surfaceDim = DarkSurface,
    surfaceBright = DarkSurfaceContainer,
    surfaceContainerLowest = Color(0xFF0D0D0D),
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurfaceVariant,
    surfaceContainerHigh = DarkSurfaceContainer,
    surfaceContainerHighest = Color(0xFF353535)
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF004D40),
    secondary = Color(0xFF00ACC1),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = LightSurfaceContainer,
    onSecondaryContainer = LightOnSurface,
    tertiary = Color(0xFF0091EA),
    onTertiary = Color(0xFFFFFFFF),
    error = LightError,
    onError = LightOnError,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = Color(0xFFE0E0E0),
    scrim = Color(0xFF000000),
    inverseSurface = DarkSurface,
    inverseOnSurface = DarkOnSurface,
    inversePrimary = DarkPrimary,
    surfaceDim = LightSurfaceVariant,
    surfaceBright = LightSurface,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurfaceVariant,
    surfaceContainerHigh = LightSurfaceContainer,
    surfaceContainerHighest = Color(0xFFD6D6D6)
)

@Composable
fun LettaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val customColors = if (darkTheme) darkCustomColors else lightCustomColors

    CompositionLocalProvider(LocalCustomColors provides customColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
