package com.letta.mobile.desktop

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
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
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.ui.ComponentStyling

@Composable
internal fun DesktopJewelTheme(content: @Composable () -> Unit) {
    val themeDefinition = remember { JewelTheme.darkThemeDefinition() }

    IntUiTheme(
        theme = themeDefinition,
        styling = ComponentStyling.decoratedWindow(),
        content = content,
    )
}

/**
 * Desktop Material theme — re-based onto the Letta palette (teal primary,
 * #0A0A0A background, #1E1E1E surfaces) instead of Jewel-derived hues, so the
 * desktop app matches the mobile design template. Desktop is dark-only today.
 *
 * `LocalCustomColors` is provided with the same fixed brand agent-status values
 * the Android `deriveCustomColors()` uses. Desktop only depends on
 * `:sharedLogic` (where `CustomColors`/`LocalCustomColors` live), not on
 * `:designsystem`, so the values are constructed inline here.
 */
@Composable
internal fun DesktopMaterialTheme(content: @Composable () -> Unit) {
    // Cool-slate palette (2026-06-23 retune) — sourced from the shared
    // LettaColorTokens so desktop and Android stay in lockstep (no duplication).
    val scheme = darkColorScheme(
        primary = Color(LettaColorTokens.DARK_PRIMARY),
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
    )

    val customColors = remember {
        CustomColors(
            userBubbleBgColor = Color(LettaColorTokens.DARK_PRIMARY_VARIANT),
            agentBubbleBgColor = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_LOW),
            reasoningBubbleBgColor = Color(0xFF143C42),
            toolBubbleBgColor = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_DEFAULT),
            systemMessageColor = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_HIGH),
            dateSeparatorColor = Color(LettaColorTokens.DARK_ON_SURFACE_VARIANT),
            textPrimary = Color(LettaColorTokens.DARK_ON_SURFACE),
            textSecondary = Color(LettaColorTokens.DARK_ON_SURFACE_VARIANT),
            textDisabled = Color(0x80AEB6C2),
            textLink = Color(0xFF00BFA5),
            textOnPrimary = Color(0xFF06302B),
            errorTextColor = Color(0xFFFFDAD6),
            successColor = Color(0xFF46C08F),
            onSuccessColor = Color(0xFF06302B),
            runningColor = Color(0xFFE0A458),
            onRunningColor = Color(0xFF2B1B00),
            agentAColor = Color(0xFF8B7CF0),
            agentBColor = Color(0xFF4C9AFF),
            agentCColor = Color(0xFFE36FB3),
            onSurfaceMutedColor = Color(LettaColorTokens.DARK_ON_SURFACE_MUTED),
            categoryPersonaColor = Color(LettaColorTokens.DARK_CATEGORY_PERSONA),
            categoryHumanColor = Color(LettaColorTokens.DARK_CATEGORY_HUMAN),
            categoryOnboardingColor = Color(LettaColorTokens.DARK_CATEGORY_ONBOARDING),
            categoryProjectColor = Color(LettaColorTokens.DARK_CATEGORY_PROJECT),
            categoryArchivalColor = Color(LettaColorTokens.DARK_CATEGORY_ARCHIVAL),
            onlineColor = Color(0xFF46C08F),
            offlineColor = Color(0xFFCF6679),
            reconnectingColor = Color(0xFFE0A458),
            iconPrimary = Color(LettaColorTokens.DARK_ON_SURFACE),
            iconSecondary = Color(LettaColorTokens.DARK_ON_SURFACE_VARIANT),
            iconAccent = Color(0xFF00BFA5),
            listItemContainerColor = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_DEFAULT),
            borderDefault = Color(LettaColorTokens.DARK_OUTLINE_VARIANT),
            borderFocused = Color(0xFF00BFA5),
            borderCritical = Color(0xFFCF6679),
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
