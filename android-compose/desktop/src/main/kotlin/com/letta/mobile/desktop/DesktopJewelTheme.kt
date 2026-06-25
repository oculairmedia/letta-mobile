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
        primary = Color(LettaColorTokens.darkPrimary),
        onPrimary = Color(0xFF06302B),
        primaryContainer = Color(LettaColorTokens.darkPrimaryVariant),
        onPrimaryContainer = Color(0xFFE6F4F1),
        secondary = Color(LettaColorTokens.tealAccent),
        onSecondary = Color(0xFF06302B),
        secondaryContainer = Color(LettaColorTokens.darkSurfaceContainerHigh),
        onSecondaryContainer = Color(LettaColorTokens.darkOnSurface),
        tertiary = Color(LettaColorTokens.cyanAccent),
        onTertiary = Color(0xFF002B30),
        tertiaryContainer = Color(0xFF143C42),
        onTertiaryContainer = Color(0xFFCFF6FB),
        background = Color(LettaColorTokens.darkBackground),
        onBackground = Color(LettaColorTokens.darkOnSurface),
        surface = Color(LettaColorTokens.darkSurface),
        onSurface = Color(LettaColorTokens.darkOnSurface),
        surfaceVariant = Color(LettaColorTokens.darkSurfaceVariant),
        onSurfaceVariant = Color(LettaColorTokens.darkOnSurfaceVariant),
        surfaceContainerLowest = Color(LettaColorTokens.darkSurfaceContainerLowest),
        surfaceContainerLow = Color(LettaColorTokens.darkSurfaceContainerLow),
        surfaceContainer = Color(LettaColorTokens.darkSurfaceContainerDefault),
        surfaceContainerHigh = Color(LettaColorTokens.darkSurfaceContainerHigh),
        surfaceContainerHighest = Color(LettaColorTokens.darkSurfaceContainerHighest),
        outline = Color(LettaColorTokens.darkOutline),
        outlineVariant = Color(LettaColorTokens.darkOutlineVariant),
        error = Color(LettaColorTokens.darkError),
        errorContainer = Color(0xFF93000A),
        onError = Color(0xFF000000),
        onErrorContainer = Color(0xFFFFDAD6),
    )

    val customColors = remember {
        CustomColors(
            userBubbleBgColor = Color(LettaColorTokens.darkPrimaryVariant),
            agentBubbleBgColor = Color(LettaColorTokens.darkSurfaceContainerLow),
            reasoningBubbleBgColor = Color(0xFF143C42),
            toolBubbleBgColor = Color(LettaColorTokens.darkSurfaceContainerDefault),
            systemMessageColor = Color(LettaColorTokens.darkSurfaceContainerHigh),
            dateSeparatorColor = Color(LettaColorTokens.darkOnSurfaceVariant),
            textPrimary = Color(LettaColorTokens.darkOnSurface),
            textSecondary = Color(LettaColorTokens.darkOnSurfaceVariant),
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
            onSurfaceMutedColor = Color(LettaColorTokens.darkOnSurfaceMuted),
            categoryPersonaColor = Color(LettaColorTokens.darkCategoryPersona),
            categoryHumanColor = Color(LettaColorTokens.darkCategoryHuman),
            categoryOnboardingColor = Color(LettaColorTokens.darkCategoryOnboarding),
            categoryProjectColor = Color(LettaColorTokens.darkCategoryProject),
            categoryArchivalColor = Color(LettaColorTokens.darkCategoryArchival),
            onlineColor = Color(0xFF46C08F),
            offlineColor = Color(0xFFCF6679),
            reconnectingColor = Color(0xFFE0A458),
            iconPrimary = Color(LettaColorTokens.darkOnSurface),
            iconSecondary = Color(LettaColorTokens.darkOnSurfaceVariant),
            iconAccent = Color(0xFF00BFA5),
            listItemContainerColor = Color(LettaColorTokens.darkSurfaceContainerDefault),
            borderDefault = Color(LettaColorTokens.darkOutlineVariant),
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
