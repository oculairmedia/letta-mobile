package com.letta.mobile.desktop

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.letta.mobile.ui.theme.CustomColors
import com.letta.mobile.ui.theme.LettaColorTokens
import com.letta.mobile.ui.theme.LocalCustomColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.ui.ComponentStyling
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.systemcolor.systemAccentColor

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun DesktopJewelTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkMode()
    val themeDefinition = remember(dark) {
        if (dark) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition()
    }
    // Jewel 0.37 was compiled against Compose 1.10's text-menu ABI. Capture
    // Compose 1.11's implementation before entering Jewel and re-provide it to
    // app content, matching Nucleus's Jewel integration compatibility bridge.
    @Suppress("DEPRECATION")
    val composeTextContextMenu = androidx.compose.foundation.text.LocalTextContextMenu.current

    IntUiTheme(
        theme = themeDefinition,
        styling = ComponentStyling.decoratedWindow(),
    ) {
        @Suppress("DEPRECATION")
        CompositionLocalProvider(
            androidx.compose.foundation.text.LocalTextContextMenu provides composeTextContextMenu,
            content = content,
        )
    }
}

/**
 * Desktop Material theme — re-based onto the Letta palette (teal primary,
 * #0A0A0A background, #1E1E1E surfaces) instead of Jewel-derived hues, so the
 * desktop app matches the mobile design template while following live OS
 * appearance and accent changes through Nucleus.
 *
 * `LocalCustomColors` is provided with the same fixed brand agent-status values
 * the Android `deriveCustomColors()` uses. Desktop only depends on
 * `:sharedLogic` (where `CustomColors`/`LocalCustomColors` live), not on
 * `:designsystem`, so the values are constructed inline here.
 */
@Composable
internal fun DesktopMaterialTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkMode()
    val systemAccent = systemAccentColor()
    // Cool-slate palette (2026-06-23 retune) — sourced from the shared
    // LettaColorTokens so desktop and Android stay in lockstep (no duplication).
    val scheme = if (dark) darkColorScheme(
        primary = systemAccent ?: Color(LettaColorTokens.DARK_PRIMARY),
        onPrimary = Color(0xFF06302B),
        primaryContainer = Color(LettaColorTokens.DARK_PRIMARY_VARIANT),
        onPrimaryContainer = Color(0xFFE6F4F1),
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
    ) else lightColorScheme(
        primary = systemAccent ?: Color(LettaColorTokens.LIGHT_PRIMARY),
        onPrimary = Color.White,
        primaryContainer = (systemAccent ?: Color(LettaColorTokens.LIGHT_PRIMARY)).copy(alpha = 0.16f),
        onPrimaryContainer = Color(LettaColorTokens.LIGHT_ON_SURFACE),
        secondary = Color(LettaColorTokens.LIGHT_PRIMARY),
        onSecondary = Color.White,
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

    val customColors = remember(scheme, dark) {
        CustomColors(
            userBubbleBgColor = scheme.primaryContainer,
            agentBubbleBgColor = scheme.surfaceContainerLow,
            reasoningBubbleBgColor = scheme.tertiaryContainer.copy(alpha = 0.72f),
            toolBubbleBgColor = scheme.surfaceContainer,
            systemMessageColor = scheme.surfaceContainerHigh,
            dateSeparatorColor = scheme.onSurfaceVariant,
            textPrimary = scheme.onSurface,
            textSecondary = scheme.onSurfaceVariant,
            textDisabled = scheme.onSurface.copy(alpha = 0.5f),
            textLink = scheme.primary,
            textOnPrimary = scheme.onPrimary,
            errorTextColor = scheme.error,
            successColor = Color(0xFF46C08F),
            onSuccessColor = Color(0xFF06302B),
            runningColor = Color(0xFFE0A458),
            onRunningColor = Color(0xFF2B1B00),
            agentAColor = Color(0xFF8B7CF0),
            agentBColor = Color(0xFF4C9AFF),
            agentCColor = Color(0xFFE36FB3),
            onSurfaceMutedColor = scheme.onSurfaceVariant.copy(alpha = 0.72f),
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
    }

    CompositionLocalProvider(LocalCustomColors provides customColors) {
        MaterialTheme(colorScheme = scheme, shapes = DesktopShapes, content = content)
    }
}

/**
 * Desktop corner-radius token scale — tighter than the Material 3 defaults
 * (4/8/12/16/28) so cards and chips read crisp rather than pill-soft. Surfaces
 * should reference `MaterialTheme.shapes.*` rather than hard-coding radii.
 */
private val DesktopShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)
