package com.letta.mobile.desktop

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.shellstate.HostAppearance
import com.letta.mobile.desktop.clientstore.DesktopHostAppearance
import com.letta.mobile.desktop.data.DesktopPreferences
import com.letta.mobile.ui.theme.HostAppearanceTheme
import com.letta.mobile.ui.theme.SharedTypography
import com.letta.mobile.ui.theme.rememberHostDark
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

/** The one appearance of the process, kept in the desktop's preferences file: every window draws it, the shell follows the host with it. */
internal val desktopHostAppearance: DesktopHostAppearance by lazy { DesktopHostAppearance(DesktopPreferences.store) }

/** The appearance every window draws: the person's choice on the host. Tests and previews provide their own. */
internal val LocalDesktopHostAppearance = staticCompositionLocalOf<HostAppearance> { desktopHostAppearance }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun DesktopJewelTheme(content: @Composable () -> Unit) {
    val dark = rememberHostDark(LocalDesktopHostAppearance.current, isSystemInDarkMode(), LocalDesktopThemeOverride.current?.dark)
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
 * Desktop Material theme: what the person chose on the host, drawn through the shared [HostAppearanceTheme]: the
 * Baken palette (vermilion primary over the North Sea Buoy deep-sea surfaces) or the colour preset the host holds, light or dark
 * as the host's theme mode says (SYSTEM follows the live OS appearance through Nucleus). Dynamic colour is Android 12+
 * only and is not drawn here.
 *
 * The OS accent color deliberately does NOT repaint `primary`: the rest of the pairing (onPrimary, containers, the
 * fixed orb gradients) is brand-derived, so a different accent (e.g. Windows red) turned the thinking ring and selection
 * markers into what read as error states. The live accent remains visible as a read-only row in Settings.
 *
 * The desktop type scale is tighter than Material 3's phone defaults so monitors get more words per line; the shapes
 * are the one scale every host uses. The phone preview picks light or dark itself ([DesktopThemeOverride]).
 */
@Composable
internal fun DesktopMaterialTheme(content: @Composable () -> Unit) {
    val override = LocalDesktopThemeOverride.current
    HostAppearanceTheme(
        appearance = LocalDesktopHostAppearance.current,
        systemDark = isSystemInDarkMode(),
        darkOverride = override?.dark,
        typography = if (override?.phoneMetrics == true) PhoneTypography else SharedTypography,
        content = content,
    )
}

/** The phone preview's type scale: Material 3's defaults, the sizes Android's theme uses (in Inter there). */
private val PhoneTypography = Typography()
