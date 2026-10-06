package com.letta.mobile.feature.chat.screen.shared

import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.haptics.HapticGates
import com.letta.mobile.ui.haptics.HapticPolicy
import com.letta.mobile.ui.haptics.Haptics
import com.letta.mobile.ui.haptics.LettaHapticCue

/**
 * letta-mobile-bglj6.1.17: the Android binding of the shared chat page's haptics seam.
 *
 * The KMP seam (PR #1771, letta-mobile-86njl.8) shipped the cue vocabulary, the rate-limiting
 * [HapticPolicy] and [LocalHaptics][com.letta.mobile.ui.haptics.LocalHaptics] — but no reader and
 * no platform binding, so every cue the shared chat page design calls for was silent. This backend
 * routes each cue to the designsystem [HapticEffects] Android realization (platform View constants
 * first, Jindong patterns for the expressive cues), behind the policy's gates: the haptics
 * setting, reduced motion (for motion-coupled cues) and the rate floors.
 *
 * The foreground gate stays true for now: a real app-foreground signal is bead letta-mobile-qjl3n,
 * and every firing site lives in the chat page's composition, which only runs while the page is
 * on screen.
 */
@Composable
internal fun rememberSharedChatHaptics(hapticsEnabled: Boolean): Haptics {
    val view = LocalView.current
    val feedback = LocalHapticFeedback.current
    val enabled by rememberUpdatedState(hapticsEnabled)
    val reducedMotion by rememberUpdatedState(rememberReducedMotionEnabled())
    return remember(view) {
        HapticPolicy(
            backend = AndroidChatHaptics(view, feedback),
            clock = SystemClock::uptimeMillis,
            gates = HapticGates(
                enabled = { enabled },
                foreground = { true },
                reducedMotion = { reducedMotion },
            ),
        )
    }
}

/** One cue, played through the designsystem Android realization ([HapticEffects.perform]). */
private class AndroidChatHaptics(
    private val view: View,
    private val feedback: HapticFeedback,
) : Haptics {
    override fun play(cue: LettaHapticCue) {
        HapticEffects.perform(cue, feedback, view)
    }
}