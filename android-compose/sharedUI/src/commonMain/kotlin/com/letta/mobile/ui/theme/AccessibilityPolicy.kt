package com.letta.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * letta-mobile-k5nbq.1: the shared reduced-motion / high-contrast policy. Hosts only emit OS
 * facts ([rememberOsReducedMotion], [rememberOsHighContrast]); this holder is the one consumers
 * and the theme read.
 */
@Immutable
data class AccessibilityPolicy(
    val reducedMotion: Boolean = false,
    val highContrast: Boolean = false,
)

val LocalAccessibilityPolicy: ProvidableCompositionLocal<AccessibilityPolicy> =
    staticCompositionLocalOf { AccessibilityPolicy() }

val LocalHighContrast: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/** Session override for the high-contrast toggle on Settings; null follows the OS. */
internal val LocalHighContrastChoice: ProvidableCompositionLocal<(Boolean) -> Unit> =
    staticCompositionLocalOf { {} }

@Composable
fun ProvideAccessibilityPolicy(content: @Composable () -> Unit) {
    var forceHighContrast by remember { mutableStateOf<Boolean?>(null) }
    val policy = rememberAccessibilityPolicy(forceHighContrast)
    CompositionLocalProvider(
        LocalAccessibilityPolicy provides policy,
        LocalReducedMotion provides policy.reducedMotion,
        LocalHighContrast provides policy.highContrast,
        LocalHighContrastChoice provides { forceHighContrast = it },
        content = content,
    )
}

@Composable
internal fun rememberAccessibilityPolicy(forceHighContrast: Boolean?): AccessibilityPolicy {
    val reduced = rememberOsReducedMotion()
    val osContrast = rememberOsHighContrast()
    val highContrast = forceHighContrast ?: osContrast
    return remember(reduced, highContrast) { AccessibilityPolicy(reduced, highContrast) }
}

@Composable
internal expect fun rememberOsReducedMotion(): Boolean

@Composable
internal expect fun rememberOsHighContrast(): Boolean
