package com.letta.mobile.ui.theme

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.annotation.VisibleForTesting
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset

/** The fixed amber warning ink for the theme's lightness, before harmonizing. */
private fun warningInk(isLightTheme: Boolean): Color =
    Color(if (isLightTheme) LettaColorTokens.LIGHT_WARNING else LettaColorTokens.DARK_WARNING)

/**
 * Warning is a fixed amber (LettaColorTokens), harmonized toward the scheme's primary, never the scheme's
 * tertiary: in the brand palette that is the calm steel grey, not a warning. Returns the ink and its container.
 */
private fun harmonizedWarningPair(colorScheme: ColorScheme, isLightTheme: Boolean): Pair<Color, Color> {
    val warningInk = warningInk(isLightTheme)
    val warningContainer = Color(
        if (isLightTheme) LettaColorTokens.LIGHT_WARNING_CONTAINER else LettaColorTokens.DARK_WARNING_CONTAINER,
    )
    val ink = HctColorHarmonizer.harmonize(stateColor = warningInk, seedColor = colorScheme.primary)
    val container = HctColorHarmonizer.harmonizeContainer(
        containerColor = warningContainer,
        seedColor = colorScheme.primary,
        contentColor = warningInk,
    )
    return ink to container
}

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
    val (harmonizedWarning, harmonizedWarningContainer) = harmonizedWarningPair(colorScheme, isLightTheme)
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
        userBubbleBgColor = lettaUserBubble(colorScheme),
        agentBubbleBgColor = colorScheme.surfaceContainerLow,
        reasoningBubbleBgColor = colorScheme.tertiaryContainer.copy(alpha = REASONING_BUBBLE_FILL_ALPHA),
        toolBubbleBgColor = colorScheme.secondaryContainer,
        systemMessageColor = colorScheme.surfaceContainerHigh,
        dateSeparatorColor = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        textPrimary = colorScheme.onSurface,
        textSecondary = colorScheme.onSurfaceVariant,
        textDisabled = colorScheme.onSurfaceVariant.copy(alpha = TEXT_DISABLED_ALPHA),
        textLink = colorScheme.primary,
        textOnPrimary = colorScheme.onPrimaryContainer,
        errorTextColor = colorScheme.onErrorContainer,
        errorContainerColor = harmonizedErrorContainer,
        harmonizedError = harmonizedError,
        harmonizedErrorContainer = harmonizedErrorContainer,
        warningTextColor = warningInk(isLightTheme),
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
        voiceYouColor = Color(if (isLightTheme) LettaColorTokens.LIGHT_VOICE_YOU else LettaColorTokens.DARK_VOICE_YOU),
        voiceAgentColor = Color(if (isLightTheme) LettaColorTokens.LIGHT_VOICE_AGENT else LettaColorTokens.DARK_VOICE_AGENT),
        onSuccessColor = Color(if (isLightTheme) 0xFFFFFFFF else 0xFF06302B),
        agentAColor = Color(if (isLightTheme) 0xFF6B4EE6 else 0xFF8B7CF0),
        agentBColor = Color(if (isLightTheme) 0xFF1A73E8 else 0xFF4C9AFF),
        agentCColor = Color(if (isLightTheme) 0xFFC03D8E else 0xFFE36FB3),
        // Muted captions + memory-block category colors (fixed brand values).
        onSurfaceMutedColor = Color(if (isLightTheme) LettaColorTokens.LIGHT_ON_SURFACE_MUTED else LettaColorTokens.DARK_ON_SURFACE_MUTED),
        categoryPersonaColor = lettaCategoryPersona(colorScheme),
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
        listItemContainerColor = colorScheme.surfaceContainer,
        selectionContainer = complementary.copy(alpha = SELECTION_FILL_ALPHA),
        onSelectionContainer = colorScheme.onSurface,
        selectionIndicator = complementary,
        borderDefault = colorScheme.outlineVariant,
        borderFocused = colorScheme.primary,
        borderCritical = harmonizedError,
    )
}

// The only caller performs the API 31 SDK check; Qodana does not propagate that guard into this helper.
@SuppressLint("NewApi")
private fun dynamicColorSchemeForApi31(context: Context, useDarkTheme: Boolean): ColorScheme =
    if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

/**
 * The wallpaper palette's scheme (Material You, Android 12+, only when the person opted into it in
 * Settings): the same accent contrast boost and high-contrast lift every other scheme gets.
 */
private fun ColorScheme.lettaWallpaperScheme(highContrast: Boolean): ColorScheme =
    withLettaContrastBoost().let { if (highContrast) it.withHighContrast() else it }

/**
 * The Material 3 theme Android draws. Its colour scheme is the one the shared theme draws: [lettaColorScheme]'s
 * Baken North Sea Buoy palette (or the chosen preset's) — byte for byte the scheme the desktop and the web
 * compose through [SharedMaterialTheme] — with the wallpaper's own colours (Material You, Android 12+) in its
 * place only when the person opted into them in Settings. High contrast flows through the same
 * [lettaColorScheme] the shared theme reads, so the accessibility lift is identical on every client. The
 * Android-only stays Android-only: the OS accessibility policy ([ProvideAccessibilityPolicy]), the expressive
 * motion scheme, the phone-density [Typography] (Inter at Material 3's phone sizes — the split from the
 * desktop's compact [SharedTypography] is by screen density, not drift), and the Letta [CustomColors] bridge
 * ([deriveCustomColors]), whose extra roles (HCT-harmonized warning and error, the fresh accent, selection)
 * only the Android design system reads.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LettaTheme(
    appTheme: AppTheme = AppTheme.SYSTEM,
    themePreset: ThemePreset = ThemePreset.DEFAULT,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val useDarkTheme = when (appTheme) {
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
        AppTheme.SYSTEM -> systemDarkTheme
    }

    val wallpaperColors = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    ProvideAccessibilityPolicy {
        val highContrast = LocalHighContrast.current
        // The desktop's scheme for the appearance, remembered as the shared theme remembers it.
        val sharedScheme = remember(useDarkTheme, highContrast, themePreset) {
            lettaColorScheme(useDarkTheme, highContrast, themePreset)
        }
        val colorScheme = if (wallpaperColors) {
            dynamicColorSchemeForApi31(context, useDarkTheme).lettaWallpaperScheme(highContrast)
        } else {
            sharedScheme
        }
        val customColors = remember(colorScheme) { deriveCustomColors(colorScheme) }

        CompositionLocalProvider(LocalCustomColors provides customColors) {
            MaterialExpressiveTheme(
                colorScheme = colorScheme,
                // The one corner scale, shared with desktop and the web.
                shapes = SharedShapes,
                typography = Typography,
                motionScheme = MotionScheme.expressive(),
                content = content,
            )
        }
    }
}
