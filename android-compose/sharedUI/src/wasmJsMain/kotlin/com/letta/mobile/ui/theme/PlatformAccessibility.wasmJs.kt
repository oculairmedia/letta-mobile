package com.letta.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.w3c.dom.MediaQueryList
import org.w3c.dom.events.Event

@Composable
internal actual fun rememberOsReducedMotion(): Boolean =
    rememberMediaQueryMatches(REDUCED_MOTION_QUERY)

@Composable
internal actual fun rememberOsHighContrast(): Boolean =
    rememberMediaQueryMatches(FORCED_COLORS_QUERY) || rememberMediaQueryMatches(MORE_CONTRAST_QUERY)

@Composable
private fun rememberMediaQueryMatches(media: String): Boolean {
    val query = remember(media) { matchMediaOrNull(media) }
    var matches by remember(query) { mutableStateOf(query?.matches == true) }
    DisposableEffect(query) {
        if (query == null) return@DisposableEffect onDispose {}
        matches = query.matches
        val onChange: (Event) -> Unit = { matches = query.matches }
        query.addEventListener("change", onChange)
        onDispose { query.removeEventListener("change", onChange) }
    }
    return matches
}

private fun matchMediaOrNull(query: String): MediaQueryList? =
    if (hasMatchMedia()) window.matchMedia(query) else null

private fun hasMatchMedia(): Boolean =
    js("typeof window !== 'undefined' && typeof window.matchMedia === 'function'")

private const val REDUCED_MOTION_QUERY = "(prefers-reduced-motion: reduce)"
private const val FORCED_COLORS_QUERY = "(forced-colors: active)"
private const val MORE_CONTRAST_QUERY = "(prefers-contrast: more)"
