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

/**
 * Timings of the border beam (lettaBorderBeam), in milliseconds. One lap and one sweep follow the
 * upstream border-beam defaults; the fade is how long the beam takes to appear or leave when its
 * `active` flag flips.
 */
object LettaBeamMotion {
    /** One full lap of an orbiting beam (Orbit and OrbitCompact). */
    const val LAP_MILLIS: Int = 2400

    /** One there-and-back pass of a sweeping beam (Sweep). */
    const val SWEEP_MILLIS: Int = 2000

    /** The fade in or out when the beam is switched on or off. */
    const val FADE_MILLIS: Int = 600

    /** Where a still beam sits under reduced motion: a fraction of the lap, past the top-right corner. */
    const val STILL_PHASE: Float = 0.15f
}

