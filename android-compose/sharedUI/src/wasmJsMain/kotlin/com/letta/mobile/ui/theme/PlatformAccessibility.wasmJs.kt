package com.letta.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal actual fun rememberOsReducedMotion(): Boolean =
    remember { wasmMediaMatches(REDUCED_MOTION_QUERY) }

@Composable
internal actual fun rememberOsHighContrast(): Boolean = remember {
    wasmMediaMatches(FORCED_COLORS_QUERY) || wasmMediaMatches(PREFERS_CONTRAST_QUERY)
}

private fun wasmMediaMatches(query: String): Boolean {
    if (query.isEmpty()) return false
    return js(
        "typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia(query).matches",
    )
}

private const val REDUCED_MOTION_QUERY = "(prefers-reduced-motion: reduce)"
private const val FORCED_COLORS_QUERY = "(forced-colors: active)"
private const val PREFERS_CONTRAST_QUERY = "(prefers-contrast: more)"
