package com.letta.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset

private data class PresetThemeColors(
    val lightScheme: ColorScheme,
    val darkScheme: ColorScheme,
)

private val DefaultThemeColors = PresetThemeColors(
    lightScheme = lightColorScheme(
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
        surfaceContainerHighest = Color(0xFFD6D6D6),
    ),
    darkScheme = darkColorScheme(
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
        surfaceContainerHighest = Color(0xFF353535),
    ),
)

private fun buildLightScheme(
    primary: Color,
    primaryContainer: Color,
    secondary: Color,
    tertiary: Color,
    background: Color,
    surface: Color,
    surfaceVariant: Color,
    outline: Color,
) = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = primaryContainer,
    onPrimaryContainer = LightOnSurface,
    secondary = secondary,
    onSecondary = Color.White,
    secondaryContainer = surfaceVariant,
    onSecondaryContainer = LightOnSurface,
    tertiary = tertiary,
    onTertiary = Color.White,
    error = LightError,
    onError = LightOnError,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = background,
    onBackground = LightOnSurface,
    surface = surface,
    onSurface = LightOnSurface,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = outline,
    outlineVariant = outline.copy(alpha = 0.45f),
    scrim = Color.Black,
    inverseSurface = DarkSurface,
    inverseOnSurface = DarkOnSurface,
    inversePrimary = primary,
    surfaceDim = surfaceVariant,
    surfaceBright = surface,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = surface,
    surfaceContainer = surfaceVariant,
    surfaceContainerHigh = primaryContainer,
    surfaceContainerHighest = primaryContainer.copy(alpha = 0.8f),
)

private fun buildDarkScheme(
    primary: Color,
    primaryContainer: Color,
    secondary: Color,
    tertiary: Color,
    background: Color,
    surface: Color,
    surfaceVariant: Color,
    outline: Color,
) = darkColorScheme(
    primary = primary,
    onPrimary = background,
    primaryContainer = primaryContainer,
    onPrimaryContainer = DarkOnSurface,
    secondary = secondary,
    onSecondary = background,
    secondaryContainer = surfaceVariant,
    onSecondaryContainer = DarkOnSurface,
    tertiary = tertiary,
    onTertiary = background,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = background,
    onBackground = DarkOnSurface,
    surface = surface,
    onSurface = DarkOnSurface,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = outline,
    outlineVariant = outline.copy(alpha = 0.6f),
    scrim = Color.Black,
    inverseSurface = LightSurface,
    inverseOnSurface = LightOnSurface,
    inversePrimary = primary,
    surfaceDim = surface,
    surfaceBright = surfaceVariant,
    surfaceContainerLowest = background,
    surfaceContainerLow = surface,
    surfaceContainer = surfaceVariant,
    surfaceContainerHigh = primaryContainer.copy(alpha = 0.65f),
    surfaceContainerHighest = primaryContainer.copy(alpha = 0.85f),
)

private fun deriveCustomColors(colorScheme: ColorScheme): CustomColors {
    val complementary = colorScheme.primary.complementary()
    val complementaryHsl = complementary.toHslColor()
    val isLightTheme = colorScheme.background.luminance() > 0.5f
    val freshAccent = Color.hsl(
        complementaryHsl.hue,
        complementaryHsl.saturation.coerceAtLeast(0.45f),
        if (isLightTheme) {
            complementaryHsl.lightness.coerceIn(0.48f, 0.62f)
        } else {
            complementaryHsl.lightness.coerceIn(0.62f, 0.74f)
        },
    )
    val freshAccentContainer = freshAccent.copy(
        alpha = if (isLightTheme) 0.18f else 0.28f,
    ).compositeOver(colorScheme.surfaceContainerHigh)
    return CustomColors(
    userBubbleBgColor = colorScheme.primaryContainer,
    agentBubbleBgColor = colorScheme.surfaceContainerLow,
    reasoningBubbleBgColor = colorScheme.tertiaryContainer.copy(alpha = 0.45f),
    toolBubbleBgColor = colorScheme.secondaryContainer,
    systemMessageColor = colorScheme.surfaceContainerHigh,
    dateSeparatorColor = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    textPrimary = colorScheme.onSurface,
    textSecondary = colorScheme.onSurfaceVariant,
    textDisabled = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    textLink = colorScheme.primary,
    textOnPrimary = colorScheme.onPrimaryContainer,
    errorTextColor = colorScheme.onErrorContainer,
    errorContainerColor = colorScheme.errorContainer,
    warningTextColor = colorScheme.onTertiaryContainer,
    warningContainerColor = colorScheme.tertiaryContainer,
    successColor = colorScheme.primary,
    successContainerColor = colorScheme.primaryContainer,
    onlineColor = colorScheme.primary,
    offlineColor = colorScheme.error,
    reconnectingColor = colorScheme.secondary,
    iconPrimary = colorScheme.onSurface,
    iconSecondary = colorScheme.onSurfaceVariant,
    iconAccent = colorScheme.primary,
    freshAccent = freshAccent,
    onFreshAccent = if (isLightTheme) Color.White else colorScheme.background,
    freshAccentContainer = freshAccentContainer,
    onFreshAccentContainer = colorScheme.onSurface,
    listItemContainerColor = colorScheme.surfaceBright,
    selectionContainer = complementary.copy(alpha = 0.15f),
    onSelectionContainer = colorScheme.onSurface,
    selectionIndicator = complementary,
    borderDefault = colorScheme.outlineVariant,
    borderFocused = colorScheme.primary,
    borderCritical = colorScheme.error,
    )
}

private val SakuraThemeColors = PresetThemeColors(
    lightScheme = buildLightScheme(
        primary = Color(0xFFB45C7B),
        primaryContainer = Color(0xFFF4D7E3),
        secondary = Color(0xFF9C6B9D),
        tertiary = Color(0xFF6E8CCB),
        background = Color(0xFFFFF8FB),
        surface = Color(0xFFFFFBFD),
        surfaceVariant = Color(0xFFF8EAF0),
        outline = Color(0xFFD8B7C4),
    ),
    darkScheme = buildDarkScheme(
        primary = Color(0xFFF0A8C1),
        primaryContainer = Color(0xFF6F3A50),
        secondary = Color(0xFFD7A8D9),
        tertiary = Color(0xFFA8C1FF),
        background = Color(0xFF171014),
        surface = Color(0xFF21161C),
        surfaceVariant = Color(0xFF35232D),
        outline = Color(0xFF76505F),
    ),
)

private val OceanThemeColors = PresetThemeColors(
    lightScheme = buildLightScheme(
        primary = Color(0xFF006D8F),
        primaryContainer = Color(0xFFC8ECF6),
        secondary = Color(0xFF007C91),
        tertiary = Color(0xFF4F6EF7),
        background = Color(0xFFF6FCFE),
        surface = Color(0xFFFBFEFF),
        surfaceVariant = Color(0xFFE4F3F8),
        outline = Color(0xFFA6C9D4),
    ),
    darkScheme = buildDarkScheme(
        primary = Color(0xFF6FD8F6),
        primaryContainer = Color(0xFF004C66),
        secondary = Color(0xFF66D9E8),
        tertiary = Color(0xFF98AEFF),
        background = Color(0xFF08141A),
        surface = Color(0xFF0E1D24),
        surfaceVariant = Color(0xFF14313A),
        outline = Color(0xFF41616B),
    ),
)

private val AmoledBlackThemeColors = PresetThemeColors(
    lightScheme = buildLightScheme(
        primary = Color(0xFF2E2E2E),
        primaryContainer = Color(0xFFE4E4E4),
        secondary = Color(0xFF5B5B5B),
        tertiary = Color(0xFF767676),
        background = Color(0xFFFAFAFA),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF0F0F0),
        outline = Color(0xFFC8C8C8),
    ),
    darkScheme = darkColorScheme(
        primary = Color(0xFFE6E6E6),
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF1A1A1A),
        onPrimaryContainer = Color(0xFFF3F3F3),
        secondary = Color(0xFFCFCFCF),
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF101010),
        onSecondaryContainer = Color(0xFFE6E6E6),
        tertiary = Color(0xFFB8B8B8),
        onTertiary = Color.Black,
        tertiaryContainer = Color(0xFF151515),
        onTertiaryContainer = Color(0xFFE0E0E0),
        error = DarkError,
        onError = DarkOnError,
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color.Black,
        onBackground = Color(0xFFF5F5F5),
        surface = Color.Black,
        onSurface = Color(0xFFF5F5F5),
        surfaceVariant = Color(0xFF101010),
        onSurfaceVariant = Color(0xFFBDBDBD),
        outline = Color(0xFF3F3F3F),
        outlineVariant = Color(0xFF262626),
        scrim = Color.Black,
        inverseSurface = Color(0xFFF5F5F5),
        inverseOnSurface = Color.Black,
        inversePrimary = Color(0xFF2E2E2E),
        surfaceDim = Color.Black,
        surfaceBright = Color(0xFF121212),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainer = Color(0xFF101010),
        surfaceContainerHigh = Color(0xFF141414),
        surfaceContainerHighest = Color(0xFF1A1A1A),
    ),
)

private val AutumnThemeColors = PresetThemeColors(
    lightScheme = buildLightScheme(
        primary = Color(0xFF9A4F1A),
        primaryContainer = Color(0xFFF4D7C4),
        secondary = Color(0xFFC16A2A),
        tertiary = Color(0xFF7D5A2F),
        background = Color(0xFFFFFBF7),
        surface = Color(0xFFFFFCFA),
        surfaceVariant = Color(0xFFF7EBDD),
        outline = Color(0xFFD3B59B),
    ),
    darkScheme = buildDarkScheme(
        primary = Color(0xFFFFB689),
        primaryContainer = Color(0xFF6D3209),
        secondary = Color(0xFFFFA763),
        tertiary = Color(0xFFE1C287),
        background = Color(0xFF17110D),
        surface = Color(0xFF221915),
        surfaceVariant = Color(0xFF362720),
        outline = Color(0xFF725746),
    ),
)

private val SpringThemeColors = PresetThemeColors(
    lightScheme = buildLightScheme(
        primary = Color(0xFF2D7D46),
        primaryContainer = Color(0xFFD8F2DC),
        secondary = Color(0xFF4E9D64),
        tertiary = Color(0xFF5C8C34),
        background = Color(0xFFF8FFF8),
        surface = Color(0xFFFBFFFB),
        surfaceVariant = Color(0xFFEAF6EA),
        outline = Color(0xFFB2D0B1),
    ),
    darkScheme = buildDarkScheme(
        primary = Color(0xFF8FDC9B),
        primaryContainer = Color(0xFF1F5630),
        secondary = Color(0xFF9FE0A8),
        tertiary = Color(0xFFC0D98A),
        background = Color(0xFF0E140E),
        surface = Color(0xFF162016),
        surfaceVariant = Color(0xFF243124),
        outline = Color(0xFF4C674D),
    ),
)

private fun presetThemeColors(themePreset: ThemePreset): PresetThemeColors = when (themePreset) {
    ThemePreset.DEFAULT -> DefaultThemeColors
    ThemePreset.OCEAN -> OceanThemeColors
    ThemePreset.AMOLED_BLACK -> AmoledBlackThemeColors
    ThemePreset.SAKURA -> SakuraThemeColors
    ThemePreset.AUTUMN -> AutumnThemeColors
    ThemePreset.SPRING -> SpringThemeColors
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LettaTheme(
    appTheme: AppTheme = AppTheme.SYSTEM,
    themePreset: ThemePreset = ThemePreset.DEFAULT,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val useDarkTheme = when (appTheme) {
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
        AppTheme.SYSTEM -> systemDarkTheme
    }

    val presetColors = presetThemeColors(themePreset)
    val useDynamicColor = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = when {
        useDynamicColor && useDarkTheme -> dynamicDarkColorScheme(context)
        useDynamicColor && !useDarkTheme -> dynamicLightColorScheme(context)
        useDarkTheme -> presetColors.darkScheme
        else -> presetColors.lightScheme
    }

    val customColors = deriveCustomColors(colorScheme)

    CompositionLocalProvider(LocalCustomColors provides customColors) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            typography = Typography,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
