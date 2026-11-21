package com.letta.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.letta.mobile.data.model.ThemePreset

// The colour presets (Default, Ocean, AMOLED black, Sakura, Autumn, Spring) and the contrast boost the
// accents get on top of them, drawn the same on every client. Android's LettaTheme adds dynamic colour
// (Android 12+) over these; desktop and the web draw them as they are.

/** The light and dark schemes of one colour preset. */
data class PresetThemeColors(
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

private fun LettaThemePaletteTokens.toLightColorScheme(): ColorScheme {
    val primaryContainer = Color(primaryContainerArgb)
    val surface = Color(surfaceArgb)
    val surfaceVariant = Color(surfaceVariantArgb)
    val outline = Color(outlineArgb)
    return lightColorScheme(
        primary = Color(primaryArgb),
        onPrimary = Color.White,
        primaryContainer = primaryContainer,
        onPrimaryContainer = LightOnSurface,
        secondary = Color(secondaryArgb),
        onSecondary = Color.White,
        secondaryContainer = surfaceVariant,
        onSecondaryContainer = LightOnSurface,
        tertiary = Color(tertiaryArgb),
        onTertiary = Color.White,
        error = LightError,
        onError = LightOnError,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(backgroundArgb),
        onBackground = LightOnSurface,
        surface = surface,
        onSurface = LightOnSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = LightOnSurfaceVariant,
        outline = outline,
        outlineVariant = outline.copy(alpha = LIGHT_OUTLINE_VARIANT_ALPHA),
        scrim = Color.Black,
        inverseSurface = DarkSurface,
        inverseOnSurface = DarkOnSurface,
        inversePrimary = Color(primaryArgb),
        surfaceDim = surfaceVariant,
        surfaceBright = surface,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = surface,
        surfaceContainer = surfaceVariant,
        surfaceContainerHigh = primaryContainer,
        surfaceContainerHighest = primaryContainer.copy(alpha = 0.8f),
    )
}

private fun LettaThemePaletteTokens.toDarkColorScheme(): ColorScheme {
    val background = Color(backgroundArgb)
    val primaryContainer = Color(primaryContainerArgb)
    val surface = Color(surfaceArgb)
    val surfaceVariant = Color(surfaceVariantArgb)
    val outline = Color(outlineArgb)
    return darkColorScheme(
        primary = Color(primaryArgb),
        onPrimary = background,
        primaryContainer = primaryContainer,
        onPrimaryContainer = DarkOnSurface,
        secondary = Color(secondaryArgb),
        onSecondary = background,
        secondaryContainer = surfaceVariant,
        onSecondaryContainer = DarkOnSurface,
        tertiary = Color(tertiaryArgb),
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
        inversePrimary = Color(primaryArgb),
        surfaceDim = surface,
        surfaceBright = surfaceVariant,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface,
        surfaceContainer = surfaceVariant,
        surfaceContainerHigh = primaryContainer.copy(alpha = 0.65f),
        surfaceContainerHighest = primaryContainer.copy(alpha = 0.85f),
    )
}

private fun LettaThemePresetTokens.toGeneratedThemeColors(): PresetThemeColors =
    PresetThemeColors(
        lightScheme = light.toLightColorScheme(),
        darkScheme = dark.toDarkColorScheme(),
    )

fun ColorScheme.withLettaContrastBoost(): ColorScheme {
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

/** The light and dark schemes of [themePreset]. */
fun presetThemeColors(themePreset: ThemePreset): PresetThemeColors = when (themePreset) {
    ThemePreset.DEFAULT -> DefaultThemeColors
    ThemePreset.OCEAN -> OceanThemeColors
    ThemePreset.AMOLED_BLACK -> AmoledBlackThemeColors
    ThemePreset.SAKURA -> SakuraThemeColors
    ThemePreset.AUTUMN -> AutumnThemeColors
    ThemePreset.SPRING -> SpringThemeColors
}
