package com.letta.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Ambient reduced-motion preference for shared Compose UI. True snaps the shared chat page's
 * motion (row placement, disclosures, label swaps, composer morphs) and stills its loops.
 *
 * [ProvideAccessibilityPolicy] at each host theme fills this from the OS. Tests and the desktop
 * phone preview may still override it.
 */
val LocalReducedMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/** The ambient reduced-motion flag. Same name the Android designsystem used to own. */
@Composable
fun rememberReducedMotionEnabled(): Boolean = LocalReducedMotion.current
