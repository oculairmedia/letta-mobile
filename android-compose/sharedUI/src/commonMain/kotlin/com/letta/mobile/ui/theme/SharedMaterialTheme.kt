package com.letta.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letta.mobile.data.model.ThemePreset

/**
 * The Material 3 theme for hosts of the shared UI that have no platform
 * theme of their own (desktop and the web): the colour [preset] (Default is the cool-slate palette from the shared
 * [LettaColorTokens]), the shared [CustomColors], and compact desktop-density [typography] and shapes. Which one is drawn
 * is the person's choice on the host: see [HostAppearanceTheme], which every such host wraps this in.
 */
@Composable
fun SharedMaterialTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    preset: ThemePreset = ThemePreset.DEFAULT,
    typography: Typography = SharedTypography,
    content: @Composable () -> Unit,
) {
    ProvideAccessibilityPolicy {
        val highContrast = LocalHighContrast.current
        val scheme = remember(darkTheme, highContrast, preset) { lettaColorScheme(darkTheme, highContrast, preset) }
        val customColors = remember(scheme) { lettaCustomColors(scheme) }

        CompositionLocalProvider(LocalCustomColors provides customColors) {
            MaterialTheme(
                colorScheme = scheme,
                shapes = SharedShapes,
                typography = typography,
                content = content,
            )
        }
    }
}

/**
 * The Material colour scheme of [preset] for hosts without a platform theme of their own (desktop, web). Default is the
 * Letta cool-slate scheme; every other preset is the preset's own light or dark scheme with the same accent contrast
 * boost Android's `LettaTheme` gives it. Dynamic colour (wallpaper colours) is Android 12+ only and is not drawn here.
 */
fun lettaColorScheme(dark: Boolean, highContrast: Boolean = false, preset: ThemePreset = ThemePreset.DEFAULT): ColorScheme {
    val base = when (preset) {
        ThemePreset.DEFAULT -> if (dark) lettaDarkScheme() else lettaLightScheme()
        else -> presetThemeColors(preset).let { if (dark) it.darkScheme else it.lightScheme }.withLettaContrastBoost()
    }
    return if (highContrast) base.withHighContrast() else base
}

private fun lettaDarkScheme(): ColorScheme = darkColorScheme(
    primary = Color(LettaColorTokens.DARK_PRIMARY),
    onPrimary = Color(LettaColorTokens.DARK_ON_PRIMARY),
    primaryContainer = Color(LettaColorTokens.DARK_PRIMARY_VARIANT),
    onPrimaryContainer = Color(LettaColorTokens.DARK_ON_PRIMARY),
    secondary = Color(LettaColorTokens.TEAL_ACCENT),
    onSecondary = Color(0xFF06302B),
    secondaryContainer = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_HIGH),
    onSecondaryContainer = Color(LettaColorTokens.DARK_ON_SURFACE),
    tertiary = Color(LettaColorTokens.CYAN_ACCENT),
    onTertiary = Color(0xFF002B30),
    tertiaryContainer = Color(0xFF143C42),
    onTertiaryContainer = Color(0xFFCFF6FB),
    background = Color(LettaColorTokens.DARK_BACKGROUND),
    onBackground = Color(LettaColorTokens.DARK_ON_SURFACE),
    surface = Color(LettaColorTokens.DARK_SURFACE),
    onSurface = Color(LettaColorTokens.DARK_ON_SURFACE),
    surfaceVariant = Color(LettaColorTokens.DARK_SURFACE_VARIANT),
    onSurfaceVariant = Color(LettaColorTokens.DARK_ON_SURFACE_VARIANT),
    surfaceContainerLowest = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_LOWEST),
    surfaceContainerLow = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_LOW),
    surfaceContainer = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_DEFAULT),
    surfaceContainerHigh = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_HIGH),
    surfaceContainerHighest = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_HIGHEST),
    outline = Color(LettaColorTokens.DARK_OUTLINE),
    outlineVariant = Color(LettaColorTokens.DARK_OUTLINE_VARIANT),
    error = Color(LettaColorTokens.DARK_ERROR),
    errorContainer = Color(0xFF93000A),
    onError = Color(0xFF000000),
    onErrorContainer = Color(0xFFFFDAD6),
)

private fun lettaLightScheme(): ColorScheme = lightColorScheme(
    primary = Color(LettaColorTokens.LIGHT_PRIMARY),
    onPrimary = Color(LettaColorTokens.LIGHT_ON_PRIMARY),
    primaryContainer = Color(LettaColorTokens.LIGHT_PRIMARY).copy(alpha = LIGHT_PRIMARY_CONTAINER_TINT),
    onPrimaryContainer = Color(LettaColorTokens.LIGHT_ON_SURFACE),
    secondary = Color(LettaColorTokens.LIGHT_PRIMARY),
    onSecondary = Color(LettaColorTokens.LIGHT_ON_PRIMARY),
    secondaryContainer = Color(LettaColorTokens.LIGHT_SURFACE_CONTAINER),
    onSecondaryContainer = Color(LettaColorTokens.LIGHT_ON_SURFACE),
    tertiary = Color(0xFF007D8A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC7F1F5),
    onTertiaryContainer = Color(0xFF002F34),
    background = Color(LettaColorTokens.LIGHT_BACKGROUND),
    onBackground = Color(LettaColorTokens.LIGHT_ON_SURFACE),
    surface = Color(LettaColorTokens.LIGHT_SURFACE),
    onSurface = Color(LettaColorTokens.LIGHT_ON_SURFACE),
    surfaceVariant = Color(LettaColorTokens.LIGHT_SURFACE_VARIANT),
    onSurfaceVariant = Color(LettaColorTokens.LIGHT_ON_SURFACE_VARIANT),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(LettaColorTokens.LIGHT_SURFACE),
    surfaceContainer = Color(LettaColorTokens.LIGHT_SURFACE_VARIANT),
    surfaceContainerHigh = Color(LettaColorTokens.LIGHT_SURFACE_CONTAINER),
    surfaceContainerHighest = Color(0xFFD2D9E2),
    outline = Color(LettaColorTokens.LIGHT_OUTLINE),
    outlineVariant = Color(LettaColorTokens.LIGHT_SURFACE_CONTAINER),
    error = Color(LettaColorTokens.LIGHT_ERROR),
    onError = Color(LettaColorTokens.LIGHT_ON_ERROR),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/** The Letta [CustomColors] derived from a [lettaColorScheme] (fixed brand status/agent colours). */
fun lettaCustomColors(scheme: ColorScheme): CustomColors =
    CustomColors(
        userBubbleBgColor = scheme.primaryContainer,
        agentBubbleBgColor = scheme.surfaceContainerLow,
        reasoningBubbleBgColor = scheme.tertiaryContainer.copy(alpha = 0.72f),
        toolBubbleBgColor = scheme.surfaceContainer,
        systemMessageColor = scheme.surfaceContainerHigh,
        dateSeparatorColor = scheme.onSurfaceVariant,
        textPrimary = scheme.onSurface,
        textSecondary = scheme.onSurfaceVariant,
        textDisabled = scheme.onSurface.copy(alpha = TEXT_DISABLED_ALPHA),
        textLink = scheme.primary,
        textOnPrimary = scheme.onPrimary,
        errorTextColor = scheme.error,
        warningTextColor = warningInk(scheme),
        warningContainerColor = warningContainer(scheme),
        harmonizedWarning = warningInk(scheme),
        harmonizedWarningContainer = warningContainer(scheme),
        successColor = Color(0xFF46C08F),
        onSuccessColor = Color(0xFF06302B),
        runningColor = Color(0xFFE0A458),
        onRunningColor = Color(0xFF2B1B00),
        voiceYouColor = Color(if (scheme.isLight()) LettaColorTokens.LIGHT_VOICE_YOU else LettaColorTokens.DARK_VOICE_YOU),
        voiceAgentColor = Color(if (scheme.isLight()) LettaColorTokens.LIGHT_VOICE_AGENT else LettaColorTokens.DARK_VOICE_AGENT),
        agentAColor = Color(0xFF8B7CF0),
        agentBColor = Color(0xFF4C9AFF),
        agentCColor = Color(0xFFE36FB3),
        onSurfaceMutedColor = scheme.onSurfaceVariant.copy(alpha = LettaDimens.Alpha.muted),
        categoryPersonaColor = Color(LettaColorTokens.DARK_CATEGORY_PERSONA),
        categoryHumanColor = Color(LettaColorTokens.DARK_CATEGORY_HUMAN),
        categoryOnboardingColor = Color(LettaColorTokens.DARK_CATEGORY_ONBOARDING),
        categoryProjectColor = Color(LettaColorTokens.DARK_CATEGORY_PROJECT),
        categoryArchivalColor = Color(LettaColorTokens.DARK_CATEGORY_ARCHIVAL),
        onlineColor = Color(0xFF46C08F),
        offlineColor = Color(0xFFCF6679),
        reconnectingColor = Color(0xFFE0A458),
        iconPrimary = scheme.onSurface,
        iconSecondary = scheme.onSurfaceVariant,
        iconAccent = scheme.primary,
        listItemContainerColor = scheme.surfaceContainer,
        borderDefault = scheme.outlineVariant,
        borderFocused = scheme.primary,
        borderCritical = scheme.error,
    )

/** The amber warning ink of [scheme]'s brightness ([LettaColorTokens.LIGHT_WARNING] / DARK_WARNING). */
private fun warningInk(scheme: ColorScheme): Color =
    Color(if (scheme.isLight()) LettaColorTokens.LIGHT_WARNING else LettaColorTokens.DARK_WARNING)

/** The amber warning container of [scheme]'s brightness, which [warningInk] reads on. */
private fun warningContainer(scheme: ColorScheme): Color =
    Color(if (scheme.isLight()) LettaColorTokens.LIGHT_WARNING_CONTAINER else LettaColorTokens.DARK_WARNING_CONTAINER)

/**
 * The compact type scale for desktop and the web: Material 3's roles, 2 to 5 sp smaller, for more words
 * per line on a monitor. Android (LettaTheme, Inter) and desktop's phone preview keep Material 3's
 * phone sizes; the split is by screen density, not drift, so it is not unified here.
 */
val SharedTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontSize = 44.sp, lineHeight = 52.sp),
        displayMedium = base.displayMedium.copy(fontSize = 36.sp, lineHeight = 44.sp),
        displaySmall = base.displaySmall.copy(fontSize = 28.sp, lineHeight = 36.sp),
        headlineLarge = base.headlineLarge.copy(fontSize = 26.sp, lineHeight = 32.sp),
        headlineMedium = base.headlineMedium.copy(fontSize = 22.sp, lineHeight = 28.sp),
        headlineSmall = base.headlineSmall.copy(fontSize = 19.sp, lineHeight = 26.sp),
        titleLarge = base.titleLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
        titleMedium = base.titleMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        titleSmall = base.titleSmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 14.sp, lineHeight = 21.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = base.labelLarge.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelMedium = base.labelMedium.copy(fontSize = 11.sp, lineHeight = 15.sp),
        labelSmall = base.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
    )
}

/**
 * The one corner scale, on every host: Android's LettaTheme, desktop (both its own and its phone
 * preview metrics) and the web all pass these shapes, so `MaterialTheme.shapes.medium` is the same
 * corner everywhere. small, medium and large are [LettaDimens.Radius] sm, md and lg, so converting a
 * `RoundedCornerShape(LettaDimens.Radius.x)` to the matching role moves nothing.
 *
 * extraSmall: menus, text fields' Material default, tooltips. small: chips, pills, small controls.
 * medium: panels, cards, list rows (LettaShapes.panel). large: composer, popups. extraLarge: dialogs
 * and sheets.
 */
val SharedShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(LettaDimens.Radius.sm),
    medium = RoundedCornerShape(LettaDimens.Radius.md),
    large = RoundedCornerShape(LettaDimens.Radius.lg),
    extraLarge = RoundedCornerShape(16.dp),
)

/**
 * How strongly the light theme's primary container is tinted with primary. It is a fill under content
 * (the user's bubble, a selected row), not content, so it sits far below the content alpha floor.
 */
private const val LIGHT_PRIMARY_CONTAINER_TINT = 0.16f

/** Whether [this] is a light scheme: its surface is light. */
private fun ColorScheme.isLight(): Boolean = surface.luminance() > LIGHT_SURFACE_LUMINANCE

private const val LIGHT_SURFACE_LUMINANCE = 0.5f

/** How strongly disabled text keeps `onSurface` (customColors.textDisabled). */
private const val TEXT_DISABLED_ALPHA = 0.5f
