package com.letta.mobile.ui.components

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Returns true if the user has disabled system animations (Settings →
 * Accessibility "Remove animations" or Developer options "Animator duration
 * scale = Animation off"; both surface as ANIMATOR_DURATION_SCALE = 0).
 *
 * Compose's tween / AnimatedContent / animate*AsState do NOT honour this
 * setting automatically; callers that want to degrade cleanly must check it
 * themselves and either zero their durations or skip the animation entirely.
 */
@Composable
fun rememberReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        } catch (_: Settings.SettingNotFoundException) {
            false
        }
    }
}
