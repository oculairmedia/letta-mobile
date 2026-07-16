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
import androidx.annotation.VisibleForTesting
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset

@VisibleForTesting
internal data class PresetThemeColors(
    val lightScheme: ColorScheme,
    val darkScheme: ColorScheme,
)

private val DefaultThemeColors = with(LettaThemeTokens.default) {
    PresetThemeColors(
        lightScheme = lightColorScheme(
            primary = Color(light.primaryArgb),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(light.primaryContainerArgb),
            onPrimaryContainer = Color(0xFF004D40),
            secondary = Color(light.secondaryArgb),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = LightSurfaceContainer,
            onSecondaryContainer = LightOnSurface,
            tertiary = Color(light.tertiaryArgb),
            onTertiary = Color(0xFFFFFFFF),
            error = LightError,
            onError = LightOnError,
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
            background = Color(light.backgroundArgb),
            onBackground = LightOnSurface,
            surface = Color(light.surfaceArgb),
            onSurface = LightOnSurface,
            surfaceVariant = Color(light.surfaceVariantArgb),
            onSurfaceVariant = LightOnSurfaceVariant,
            outline = Color(light.outlineArgb),
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
            primary = Color(dark.primaryArgb),
            onPrimary = DarkBackground,
            primaryContainer = Color(dark.primaryContainerArgb),
            onPrimaryContainer = DarkOnSurface,
            secondary = Color(dark.secondaryArgb),
            onSecondary = DarkBackground,
            secondaryContainer = DarkSurfaceContainer,
            onSecondaryContainer = DarkOnSurface,
            tertiary = Color(dark.tertiaryArgb),
            onTertiary = DarkBackground,
            error = DarkError,
            onError = DarkOnError,
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            background = Color(dark.backgroundArgb),
            onBackground = DarkOnSurface,
            surface = Color(dark.surfaceArgb),
            onSurface = DarkOnSurface,
            surfaceVariant = Color(dark.surfaceVariantArgb),
            onSurfaceVariant = DarkOnSurfaceVariant,
            outline = Color(dark.outlineArgb),
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
}

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

private fun LettaThemePaletteTokens.toLightColorScheme(): ColorScheme =
    buildLightScheme(
        primary = Color(primaryArgb),
        primaryContainer = Color(primaryContainerArgb),
        secondary = Color(secondaryArgb),
        tertiary = Color(tertiaryArgb),
        background = Color(backgroundArgb),
        surface = Color(surfaceArgb),
        surfaceVariant = Color(surfaceVariantArgb),
        outline = Color(outlineArgb),
    )

private fun LettaThemePaletteTokens.toDarkColorScheme(): ColorScheme =
    buildDarkScheme(
        primary = Color(primaryArgb),
        primaryContainer = Color(primaryContainerArgb),
        secondary = Color(secondaryArgb),
        tertiary = Color(tertiaryArgb),
        background = Color(backgroundArgb),
        surface = Color(surfaceArgb),
        surfaceVariant = Color(surfaceVariantArgb),
        outline = Color(outlineArgb),
    )

private fun LettaThemePresetTokens.toGeneratedThemeColors(): PresetThemeColors =
    PresetThemeColors(
        lightScheme = light.toLightColorScheme(),
        darkScheme = dark.toDarkColorScheme(),
    )

@VisibleForTesting
internal fun ColorScheme.withLettaContrastBoost(): ColorScheme {
    val isLightTheme = background.luminance() > 0.5f
    val boostedPrimary = primary.boostAccentContrast(isLightTheme)
    val boostedSecondary = secondary.boostAccentContrast(isLightTheme)
    val boostedTertiary = tertiary.boostAccentContrast(isLightTheme)
    return copy(
        primary = boostedPrimary,
        primaryContainer = boostedPrimary.toAccentContainer(isLightTheme),
        onPrimaryContainer = onSurface,
        secondary = boostedSecondary,
        secondaryContainer = boostedSecondary.toAccentContainer(isLightTheme),
        onSecondaryContainer = onSurface,
        tertiary = boostedTertiary,
        tertiaryContainer = boostedTertiary.toAccentContainer(isLightTheme),
        onTertiaryContainer = onSurface,
        onSurfaceVariant = onSurfaceVariant.boostSupportingTextContrast(isLightTheme),
        outline = outline.boostOutlineContrast(isLightTheme),
        outlineVariant = outlineVariant.boostOutlineVariantContrast(isLightTheme),
    )
}

private fun Color.boostAccentContrast(isLightTheme: Boolean): Color {
    val hsl = toHslColor()
    val saturation = if (hsl.saturation < NeutralSaturationThreshold) {
        hsl.saturation
    } else {
        hsl.saturation.coerceAtLeast(if (isLightTheme) 0.58f else 0.72f)
    }
    val lightness = if (isLightTheme) {
        hsl.lightness.coerceIn(0.30f, 0.46f)
    } else {
        hsl.lightness.coerceIn(0.68f, 0.84f)
    }
    return Color.hsl(hsl.hue, saturation, lightness, alpha)
}

private fun Color.toAccentContainer(isLightTheme: Boolean): Color {
    val hsl = toHslColor()
    val saturation = if (hsl.saturation < NeutralSaturationThreshold) {
        hsl.saturation
    } else {
        hsl.saturation.coerceAtLeast(if (isLightTheme) 0.32f else 0.42f)
    }
    val lightness = if (isLightTheme) 0.86f else 0.26f
    return Color.hsl(hsl.hue, saturation, lightness)
}

private fun Color.boostSupportingTextContrast(isLightTheme: Boolean): Color {
    val hsl = toHslColor()
    val lightness = if (isLightTheme) {
        hsl.lightness.coerceAtMost(0.26f)
    } else {
        hsl.lightness.coerceAtLeast(0.78f)
    }
    return Color.hsl(hsl.hue, hsl.saturation, lightness, alpha)
}

private fun Color.boostOutlineContrast(isLightTheme: Boolean): Color {
    val hsl = toHslColor()
    val lightness = if (isLightTheme) {
        hsl.lightness.coerceAtMost(0.62f)
    } else {
        hsl.lightness.coerceAtLeast(0.42f)
    }
    return Color.hsl(hsl.hue, hsl.saturation, lightness, alpha)
}

private fun Color.boostOutlineVariantContrast(isLightTheme: Boolean): Color {
    val hsl = toHslColor()
    val lightness = if (isLightTheme) {
        hsl.lightness.coerceAtMost(0.72f)
    } else {
        hsl.lightness.coerceAtLeast(0.32f)
    }
    return Color.hsl(hsl.hue, hsl.saturation, lightness, alpha)
}

private const val NeutralSaturationThreshold = 0.08f

@VisibleForTesting
internal fun deriveCustomColors(colorScheme: ColorScheme): CustomColors {
    val complementary = colorScheme.primary.complementary()
    val complementaryHsl = complementary.toHslColor()
    val isLightTheme = colorScheme.background.luminance() > 0.5f
    val harmonizedError = HctColorHarmonizer.harmonize(
        stateColor = colorScheme.error,
        seedColor = colorScheme.primary,
    )
    val harmonizedErrorContainer = HctColorHarmonizer.harmonizeContainer(
        containerColor = colorScheme.errorContainer,
        seedColor = colorScheme.primary,
        contentColor = colorScheme.onErrorContainer,
    )
    val harmonizedWarning = HctColorHarmonizer.harmonize(
        stateColor = colorScheme.tertiary,
        seedColor = colorScheme.primary,
    )
    val harmonizedWarningContainer = HctColorHarmonizer.harmonizeContainer(
        containerColor = colorScheme.tertiaryContainer,
        seedColor = colorScheme.primary,
        contentColor = colorScheme.onTertiaryContainer,
    )
    val harmonizedSuccess = HctColorHarmonizer.harmonize(
        stateColor = colorScheme.primary,
        seedColor = colorScheme.primary,
    )
    val harmonizedSuccessContainer = HctColorHarmonizer.harmonizeContainer(
        containerColor = colorScheme.primaryContainer,
        seedColor = colorScheme.primary,
        contentColor = colorScheme.onPrimaryContainer,
    )
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
        errorContainerColor = harmonizedErrorContainer,
        harmonizedError = harmonizedError,
        harmonizedErrorContainer = harmonizedErrorContainer,
        warningTextColor = colorScheme.onTertiaryContainer,
        warningContainerColor = harmonizedWarningContainer,
        harmonizedWarning = harmonizedWarning,
        harmonizedWarningContainer = harmonizedWarningContainer,
        // Agent status: fixed brand values so they match the design template
        // across every preset (not derived from the dynamic scheme).
        successColor = Color(if (isLightTheme) 0xFF2E9E73 else 0xFF46C08F),
        successContainerColor = harmonizedSuccessContainer,
        harmonizedSuccess = harmonizedSuccess,
        harmonizedSuccessContainer = harmonizedSuccessContainer,
        runningColor = Color(if (isLightTheme) 0xFFB26A00 else 0xFFE0A458),
        onRunningColor = Color(if (isLightTheme) 0xFFFFFFFF else 0xFF2B1B00),
        onSuccessColor = Color(if (isLightTheme) 0xFFFFFFFF else 0xFF06302B),
        agentAColor = Color(if (isLightTheme) 0xFF6B4EE6 else 0xFF8B7CF0),
        agentBColor = Color(if (isLightTheme) 0xFF1A73E8 else 0xFF4C9AFF),
        agentCColor = Color(if (isLightTheme) 0xFFC03D8E else 0xFFE36FB3),
        // Muted captions + memory-block category colors (fixed brand values).
        onSurfaceMutedColor = Color(if (isLightTheme) 0xFF6B7480 else 0xFF717A87),
        categoryPersonaColor = Color(0xFF00BFA5),
        categoryHumanColor = Color(if (isLightTheme) 0xFF2F6FB0 else 0xFF5C9BD6),
        categoryOnboardingColor = Color(if (isLightTheme) 0xFF9A6A1F else 0xFFD1A05A),
        categoryProjectColor = Color(if (isLightTheme) 0xFF6E5BB8 else 0xFF9B8AE0),
        categoryArchivalColor = Color(if (isLightTheme) 0xFF5F6469 else 0xFF828B98),
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
        borderCritical = harmonizedError,
    )
}

private val SakuraThemeColors = LettaThemeTokens.sakura.toGeneratedThemeColors()

private val OceanThemeColors = LettaThemeTokens.ocean.toGeneratedThemeColors()

private val AmoledBlackThemeColors = with(LettaThemeTokens.amoledBlack) {
    PresetThemeColors(
        lightScheme = light.toLightColorScheme(),
        darkScheme = darkColorScheme(
            primary = Color(dark.primaryArgb),
            onPrimary = Color.Black,
            primaryContainer = Color(dark.primaryContainerArgb),
            onPrimaryContainer = Color(0xFFF3F3F3),
            secondary = Color(dark.secondaryArgb),
            onSecondary = Color.Black,
            secondaryContainer = Color.Black,
            onSecondaryContainer = Color(0xFFE6E6E6),
            tertiary = Color(dark.tertiaryArgb),
            onTertiary = Color.Black,
            tertiaryContainer = Color.Black,
            onTertiaryContainer = Color(0xFFE0E0E0),
            error = DarkError,
            onError = DarkOnError,
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            background = Color(dark.backgroundArgb),
            onBackground = Color(0xFFF5F5F5),
            surface = Color(dark.surfaceArgb),
            onSurface = Color(0xFFF5F5F5),
            surfaceVariant = Color(dark.surfaceVariantArgb),
            onSurfaceVariant = Color(0xFFBDBDBD),
            outline = Color(dark.outlineArgb),
            outlineVariant = Color(0xFF262626),
            scrim = Color.Black,
            inverseSurface = Color(0xFFF5F5F5),
            inverseOnSurface = Color.Black,
            inversePrimary = Color(0xFF2E2E2E),
            surfaceDim = Color.Black,
            surfaceBright = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color.Black,
            surfaceContainer = Color.Black,
            surfaceContainerHigh = Color.Black,
            surfaceContainerHighest = Color.Black,
        ),
    )
}

private val AutumnThemeColors = LettaThemeTokens.autumn.toGeneratedThemeColors()

private val SpringThemeColors = LettaThemeTokens.spring.toGeneratedThemeColors()

@VisibleForTesting
internal fun presetThemeColors(themePreset: ThemePreset): PresetThemeColors = when (themePreset) {
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

    val baseColorScheme = when {
        useDynamicColor && useDarkTheme -> dynamicDarkColorScheme(context)
        useDynamicColor && !useDarkTheme -> dynamicLightColorScheme(context)
        useDarkTheme -> presetColors.darkScheme
        else -> presetColors.lightScheme
    }
    val colorScheme = baseColorScheme.withLettaContrastBoost()

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
