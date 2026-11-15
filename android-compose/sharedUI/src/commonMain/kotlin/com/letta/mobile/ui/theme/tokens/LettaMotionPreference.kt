package com.letta.mobile.ui.theme.tokens

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Ambient reduced-motion preference for shared Compose UI. True snaps the shared motion (row
 * placement, disclosures, label swaps, composer morphs, status glyphs) and stills its loops.
 *
 * ProvideAccessibilityPolicy at each host theme fills this from the OS; tests and the desktop
 * phone preview may still override it. It sits with the leaf tokens so the shared components in
 * ui.components.foundation and ui.components.disclosure can honour it without importing ui.theme.
 */
val LocalLettaReducedMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }
