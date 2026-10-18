package com.letta.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal actual fun rememberOsReducedMotion(): Boolean = remember { prefersReducedMotion() }

@Composable
internal actual fun rememberOsHighContrast(): Boolean =
    remember { forcedColorsActive() || prefersMoreContrast() }

private fun prefersReducedMotion(): Boolean =
    js("typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches")

private fun forcedColorsActive(): Boolean =
    js("typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(forced-colors: active)').matches")

private fun prefersMoreContrast(): Boolean =
    js("typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(prefers-contrast: more)').matches")
