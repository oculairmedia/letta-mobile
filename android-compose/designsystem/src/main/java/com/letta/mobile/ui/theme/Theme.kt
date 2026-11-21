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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.annotation.VisibleForTesting
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset

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
        voiceYouColor = Color(if (isLightTheme) LettaColorTokens.LIGHT_VOICE_YOU else LettaColorTokens.DARK_VOICE_YOU),
        voiceAgentColor = Color(if (isLightTheme) LettaColorTokens.LIGHT_VOICE_AGENT else LettaColorTokens.DARK_VOICE_AGENT),
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
    val baseColorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicColorSchemeForApi31(context, useDarkTheme)
    } else {
        if (useDarkTheme) presetColors.darkScheme else presetColors.lightScheme
    }
    ProvideAccessibilityPolicy {
        val highContrast = LocalHighContrast.current
        val colorScheme = baseColorScheme.withLettaContrastBoost().let { boosted ->
            if (highContrast) boosted.withHighContrast() else boosted
        }
        val customColors = deriveCustomColors(colorScheme)

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
