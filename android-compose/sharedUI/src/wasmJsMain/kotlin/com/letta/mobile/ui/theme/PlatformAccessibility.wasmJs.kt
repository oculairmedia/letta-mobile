package com.letta.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

@Composable
internal actual fun rememberOsReducedMotion(): Boolean {
    var reduced by remember { mutableStateOf(prefersReducedMotion()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MEDIA_POLL_INTERVAL_MS)
            reduced = prefersReducedMotion()
        }
    }
    return reduced
}

@Composable
internal actual fun rememberOsHighContrast(): Boolean {
    var highContrast by remember { mutableStateOf(wasmOsHighContrast()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MEDIA_POLL_INTERVAL_MS)
            highContrast = wasmOsHighContrast()
        }
    }
    return highContrast
}

private fun wasmOsHighContrast(): Boolean = forcedColorsActive() || prefersMoreContrast()

private fun prefersReducedMotion(): Boolean =
    js("typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches")

private fun forcedColorsActive(): Boolean =
    js("typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(forced-colors: active)').matches")

private fun prefersMoreContrast(): Boolean =
    js("typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(prefers-contrast: more)').matches")

private const val MEDIA_POLL_INTERVAL_MS = 1_000L
