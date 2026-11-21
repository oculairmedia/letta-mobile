package com.letta.mobile.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.letta.mobile.data.settings.AppearanceSettings
import com.letta.mobile.data.settings.hostAppearanceSettings
import com.letta.mobile.data.shellstate.HostAppearance

/**
 * The Appearance settings of a client whose appearance is the host's ([HostAppearance]), kept for as long as the page that
 * asks is composed: see [hostAppearanceSettings]. [dynamicColorSupported] is true only where the OS has wallpaper colours
 * (Android 12+); desktop and the web leave it false, so the row is not offered.
 */
@Composable
fun rememberHostAppearanceSettings(appearance: HostAppearance, dynamicColorSupported: Boolean = false): AppearanceSettings {
    val scope = rememberCoroutineScope()
    return remember(appearance, scope, dynamicColorSupported) { hostAppearanceSettings(appearance, scope, dynamicColorSupported) }
}
