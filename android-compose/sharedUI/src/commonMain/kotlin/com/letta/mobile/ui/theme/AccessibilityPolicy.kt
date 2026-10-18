package com.letta.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

/**
 * Persists the user's explicit high-contrast toggle in [store] (`null` follows the OS setting)
 * and shares one reactive state across any nested theme wrappers in the same window/activity.
 */
class HighContrastPreference(
    private val store: SecureSettingsStore = InMemorySecureSettingsStore(),
) {
    private val _override = MutableStateFlow(readStored())
    val override: StateFlow<Boolean?> = _override.asStateFlow()

    fun set(enabled: Boolean) {
        runCatching { store.putString(HIGH_CONTRAST_PREF_KEY, enabled.toString()) }
        _override.value = enabled
    }

    fun clear() {
        runCatching { store.remove(HIGH_CONTRAST_PREF_KEY) }
        _override.value = null
    }

    private fun readStored(): Boolean? =
        runCatching { store.getString(HIGH_CONTRAST_PREF_KEY)?.toBooleanStrictOrNull() }.getOrNull()
}

val LocalHighContrastPreference: ProvidableCompositionLocal<HighContrastPreference?> =
    staticCompositionLocalOf { null }

val LocalAccessibilityPolicy: ProvidableCompositionLocal<AccessibilityPolicy> =
    staticCompositionLocalOf { AccessibilityPolicy() }

val LocalHighContrast: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/** Session/persistent override for the high-contrast toggle on Settings; null follows the OS. */
internal val LocalHighContrastChoice: ProvidableCompositionLocal<(Boolean) -> Unit> =
    staticCompositionLocalOf { {} }

@Composable
fun ProvideAccessibilityPolicy(
    preference: HighContrastPreference? = LocalHighContrastPreference.current,
    content: @Composable () -> Unit,
) {
    val activePreference = preference ?: remember { HighContrastPreference() }
    val forceHighContrast by activePreference.override.collectAsState()
    val policy = rememberAccessibilityPolicy(forceHighContrast)
    CompositionLocalProvider(
        LocalHighContrastPreference provides activePreference,
        LocalAccessibilityPolicy provides policy,
        LocalReducedMotion provides policy.reducedMotion,
        LocalHighContrast provides policy.highContrast,
        LocalHighContrastChoice provides activePreference::set,
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

internal const val HIGH_CONTRAST_PREF_KEY = "accessibility.highContrast"
