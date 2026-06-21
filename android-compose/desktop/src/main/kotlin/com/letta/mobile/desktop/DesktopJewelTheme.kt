package com.letta.mobile.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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

@Composable
internal fun DesktopMaterialTheme(content: @Composable () -> Unit) {
    val colors = JewelTheme.globalColors

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = colors.outlines.focused,
            onPrimary = Color.White,
            primaryContainer = colors.outlines.focused.copy(alpha = 0.24f),
            onPrimaryContainer = colors.text.normal,
            secondary = colors.text.info,
            onSecondary = colors.panelBackground,
            secondaryContainer = colors.borders.normal.copy(alpha = 0.42f),
            onSecondaryContainer = colors.text.normal,
            tertiary = colors.outlines.warning,
            onTertiary = colors.panelBackground,
            tertiaryContainer = colors.outlines.warning.copy(alpha = 0.20f),
            onTertiaryContainer = colors.text.warning,
            error = colors.text.error,
            errorContainer = colors.text.error.copy(alpha = 0.20f),
            onErrorContainer = colors.text.normal,
            surface = colors.panelBackground,
            surfaceVariant = colors.borders.focused.copy(alpha = 0.34f),
            onSurface = colors.text.normal,
            onSurfaceVariant = colors.text.info,
            outline = colors.borders.normal,
            outlineVariant = colors.borders.disabled,
        ),
        content = content,
    )
}
