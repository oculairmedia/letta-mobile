package com.letta.mobile.ui.theme

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Ambient reduced-motion preference for shared Compose UI.
 *
 * Nothing provides this local yet. Every reader sees the default `false`, so
 * disclosure chevrons still animate. Wiring a provider into the Android and
 * desktop shells is letta-mobile-eohab.5.
 */
val LocalReducedMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }
