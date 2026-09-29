package com.letta.mobile.ui.theme

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Ambient reduced-motion preference for shared Compose UI.
 *
 * Defaults to `false`. Platform shells (e.g. Android `LettaTheme`, Desktop window host)
 * provide the system accessibility setting here.
 */
val LocalReducedMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }
