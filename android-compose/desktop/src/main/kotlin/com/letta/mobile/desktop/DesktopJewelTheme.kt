package com.letta.mobile.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.letta.mobile.ui.theme.CustomColors
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
    val scheme = darkColorScheme(
        primary = Color(0xFF00BFA5),
        onPrimary = Color(0xFF06302B),
        primaryContainer = Color(0xFF009688),
        onPrimaryContainer = Color(0xFFE6F4F1),
        secondary = Color(0xFF1DE9B6),
        onSecondary = Color(0xFF06302B),
        secondaryContainer = Color(0xFF2A2A2A),
        onSecondaryContainer = Color(0xFFE0E0E0),
        tertiary = Color(0xFF00E5FF),
        onTertiary = Color(0xFF002B30),
        tertiaryContainer = Color(0xFF143C42),
        onTertiaryContainer = Color(0xFFCFF6FB),
        background = Color(0xFF0A0A0A),
        onBackground = Color(0xFFE0E0E0),
        surface = Color(0xFF0A0A0A),
        onSurface = Color(0xFFE0E0E0),
        surfaceVariant = Color(0xFF1E1E1E),
        onSurfaceVariant = Color(0xFFBDBDBD),
        surfaceContainerLowest = Color(0xFF0D0D0D),
        surfaceContainerLow = Color(0xFF121212),
        surfaceContainer = Color(0xFF1E1E1E),
        surfaceContainerHigh = Color(0xFF2A2A2A),
        surfaceContainerHighest = Color(0xFF353535),
        outline = Color(0xFF424242),
        outlineVariant = Color(0xFF303030),
        error = Color(0xFFCF6679),
        errorContainer = Color(0xFF93000A),
        onError = Color(0xFF000000),
        onErrorContainer = Color(0xFFFFDAD6),
    )

    val customColors = remember {
        CustomColors(
            userBubbleBgColor = Color(0xFF009688),
            agentBubbleBgColor = Color(0xFF121212),
            reasoningBubbleBgColor = Color(0xFF143C42),
            toolBubbleBgColor = Color(0xFF1E1E1E),
            systemMessageColor = Color(0xFF2A2A2A),
            dateSeparatorColor = Color(0xFFBDBDBD),
            textPrimary = Color(0xFFE0E0E0),
            textSecondary = Color(0xFFBDBDBD),
            textDisabled = Color(0x80BDBDBD),
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
            onlineColor = Color(0xFF46C08F),
            offlineColor = Color(0xFFCF6679),
            reconnectingColor = Color(0xFFE0A458),
            iconPrimary = Color(0xFFE0E0E0),
            iconSecondary = Color(0xFFBDBDBD),
            iconAccent = Color(0xFF00BFA5),
            listItemContainerColor = Color(0xFF1E1E1E),
            borderDefault = Color(0xFF303030),
            borderFocused = Color(0xFF00BFA5),
            borderCritical = Color(0xFFCF6679),
        )
    }

    CompositionLocalProvider(LocalCustomColors provides customColors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
