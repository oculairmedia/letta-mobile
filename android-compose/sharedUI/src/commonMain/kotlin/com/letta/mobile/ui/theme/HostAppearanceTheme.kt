package com.letta.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.letta.mobile.data.model.appThemeNamed
import com.letta.mobile.data.model.themePresetNamed
import com.letta.mobile.data.shellstate.HostAppearance

/**
 * The theme of a client whose look is the person's choice on the host ([HostAppearance]): the host's theme mode (SYSTEM
 * follows the OS: [systemDark]) and colour preset, drawn through [SharedMaterialTheme]. It redraws when the choice changes
 * (this device's or another's) and, on a cold start, draws the last document this device mirrored before the host answers.
 * An unknown name or no choice at all is the default, which follows the OS.
 *
 * [darkOverride], when a host has its own reason (the phone preview picks light or dark itself), wins over the theme mode.
 */
@Composable
fun HostAppearanceTheme(
    appearance: HostAppearance,
    systemDark: Boolean = isSystemInDarkTheme(),
    darkOverride: Boolean? = null,
    typography: Typography = SharedTypography,
    content: @Composable () -> Unit,
) {
    val shown by appearance.values.collectAsState(appearance.current)
    SharedMaterialTheme(
        darkTheme = darkOverride ?: appThemeNamed(shown.theme).isDark(systemDark),
        preset = themePresetNamed(shown.themePreset),
        typography = typography,
        content = content,
    )
}

/**
 * Whether the host's theme mode draws dark now, for the parts of a window that sit outside the theme (a native title bar,
 * a toolkit's own styling): the same answer [HostAppearanceTheme] gives for the same [systemDark] and [darkOverride].
 */
@Composable
fun rememberHostDark(appearance: HostAppearance, systemDark: Boolean, darkOverride: Boolean? = null): Boolean {
    val shown by appearance.values.collectAsState(appearance.current)
    return darkOverride ?: appThemeNamed(shown.theme).isDark(systemDark)
}
