package com.letta.mobile.ui.a2ui

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * Cross-platform haptic vocabulary for A2UI widgets.
 *
 * A2UI moved from the Android-only designsystem module into sharedLogic's
 * jvmAndAndroid source set (letta-mobile-2don7) so desktop can render the
 * same surfaces. The Android-only `com.letta.mobile.ui.haptics.HapticEffects`
 * (View-based platform haptic constants + Jindong pattern player) stays in
 * designsystem and is NOT reachable from here, so A2UI widgets route through
 * Compose's built-in cross-platform [HapticFeedback] instead. This is a
 * deliberate, disclosed simplification: Android loses HapticEffects' richer
 * per-device vocabulary specifically for A2UI-rendered controls, but every
 * cue below matches the same [HapticFeedbackType] HapticEffects itself falls
 * back to when the platform-specific View haptic isn't available (see
 * designsystem's HapticEffects.platformSpecFor). Desktop gets the same
 * semantic cues via Compose Multiplatform's implementation (a no-op today,
 * but wired for when the desktop runtime adds a backend).
 */
internal object A2uiHaptics {
    fun confirm(haptic: HapticFeedback) = haptic.performHapticFeedback(HapticFeedbackType.Confirm)
    fun reject(haptic: HapticFeedback) = haptic.performHapticFeedback(HapticFeedbackType.Reject)
    fun toggleOn(haptic: HapticFeedback) = haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
    fun toggleOff(haptic: HapticFeedback) = haptic.performHapticFeedback(HapticFeedbackType.ToggleOff)
    fun segmentTick(haptic: HapticFeedback) = haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
    fun contextClick(haptic: HapticFeedback) = haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
    fun longPress(haptic: HapticFeedback) = haptic.performHapticFeedback(HapticFeedbackType.LongPress)
}
