package com.letta.mobile.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.ui.theme.LocalCustomColors
import com.letta.mobile.ui.theme.LocalHighContrast
import com.letta.mobile.ui.theme.ProvideAccessibilityPolicy
import com.letta.mobile.ui.theme.SharedShapes
import com.letta.mobile.ui.theme.SharedTypography
import com.letta.mobile.ui.theme.lettaColorScheme
import com.letta.mobile.ui.theme.lettaCustomColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.ui.ComponentStyling
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode

/**
 * A host's override of the desktop theme: the phone preview (`:desktop:runPhone`) picks light or dark
 * itself and draws with the phone's Material type scale and shapes. Null (the default) follows the OS
 * and keeps the desktop metrics.
 */
@Immutable
internal data class DesktopThemeOverride(
    val dark: Boolean,
    /** Material 3's default type scale and shapes (Android's sizes) instead of the desktop's tighter ones. */
    val phoneMetrics: Boolean = false,
)

internal val LocalDesktopThemeOverride = staticCompositionLocalOf<DesktopThemeOverride?> { null }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun DesktopJewelTheme(content: @Composable () -> Unit) {
    val dark = LocalDesktopThemeOverride.current?.dark ?: isSystemInDarkMode()
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
 * light/dark appearance changes through Nucleus.
 *
 * The OS accent color deliberately does NOT repaint `primary`: the rest of the
 * pairing (onPrimary, containers, the fixed orb gradients) is brand-derived,
 * so a non-teal accent (e.g. Windows red) turned the thinking ring and
 * selection markers into what read as error states. The live accent remains
 * visible as a read-only row in Settings.
 *
 * The scheme, `LocalCustomColors`, type scale and shapes are the shared
 * [lettaColorScheme], [lettaCustomColors], [SharedTypography] and
 * [SharedShapes] (also behind the web host's `SharedMaterialTheme`); the
 * desktop type scale and shapes are tighter than Material 3's phone defaults
 * so monitors get more words per line and crisper corners.
 */
@Composable
internal fun DesktopMaterialTheme(content: @Composable () -> Unit) {
    ProvideAccessibilityPolicy {
        val override = LocalDesktopThemeOverride.current
        val dark = override?.dark ?: isSystemInDarkMode()
        val highContrast = LocalHighContrast.current
        val scheme = remember(dark, highContrast) { lettaColorScheme(dark, highContrast) }
        val customColors = remember(scheme) { lettaCustomColors(scheme) }

        CompositionLocalProvider(LocalCustomColors provides customColors) {
            val phoneMetrics = override?.phoneMetrics == true
            MaterialTheme(
                colorScheme = scheme,
                shapes = if (phoneMetrics) PhoneShapes else SharedShapes,
                typography = if (phoneMetrics) PhoneTypography else SharedTypography,
                content = content,
            )
        }
    }
}

/** The phone preview's type scale: Material 3's defaults, the sizes Android's theme uses (in Inter there). */
private val PhoneTypography = Typography()

/** The phone preview's shapes: Material 3's defaults, as on Android. */
private val PhoneShapes = Shapes()
