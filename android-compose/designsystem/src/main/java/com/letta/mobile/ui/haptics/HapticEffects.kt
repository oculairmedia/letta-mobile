package com.letta.mobile.ui.haptics

import android.annotation.SuppressLint
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * Centralized semantic haptic vocabulary for Letta UI.
 *
 * Compose exposes newer Android haptic effects as [HapticFeedbackType] values,
 * but several of the expressive effects only map cleanly on Android 14+. This
 * helper keeps that API-level decision in one place and gives older devices a
 * conservative view-level fallback before falling back to Compose LongPress.
 */
object HapticEffects {
    fun confirm(haptic: HapticFeedback, view: View? = null, enabled: Boolean = true) {
        performExpressive(
            enabled = enabled,
            haptic = haptic,
            view = view,
            composeType = HapticFeedbackType.Confirm,
            fallbackPlatformType = HapticFeedbackConstants.CONTEXT_CLICK,
        )
    }

    fun reject(haptic: HapticFeedback, view: View? = null, enabled: Boolean = true) {
        performExpressive(
            enabled = enabled,
            haptic = haptic,
            view = view,
            composeType = HapticFeedbackType.Reject,
            fallbackPlatformType = HapticFeedbackConstants.LONG_PRESS,
        )
    }

    fun toggleOn(haptic: HapticFeedback, view: View? = null, enabled: Boolean = true) {
        performExpressive(
            enabled = enabled,
            haptic = haptic,
            view = view,
            composeType = HapticFeedbackType.ToggleOn,
            fallbackPlatformType = HapticFeedbackConstants.CLOCK_TICK,
        )
    }

    fun toggleOff(haptic: HapticFeedback, view: View? = null, enabled: Boolean = true) {
        performExpressive(
            enabled = enabled,
            haptic = haptic,
            view = view,
            composeType = HapticFeedbackType.ToggleOff,
            fallbackPlatformType = HapticFeedbackConstants.CLOCK_TICK,
        )
    }

    fun segmentTick(haptic: HapticFeedback, view: View? = null, enabled: Boolean = true) {
        performExpressive(
            enabled = enabled,
            haptic = haptic,
            view = view,
            composeType = HapticFeedbackType.SegmentTick,
            fallbackPlatformType = HapticFeedbackConstants.CLOCK_TICK,
        )
    }

    fun gestureThreshold(haptic: HapticFeedback, view: View? = null, enabled: Boolean = true) {
        performExpressive(
            enabled = enabled,
            haptic = haptic,
            view = view,
            composeType = HapticFeedbackType.GestureThresholdActivate,
            fallbackPlatformType = HapticFeedbackConstants.LONG_PRESS,
        )
    }

    fun contextClick(haptic: HapticFeedback, view: View? = null, enabled: Boolean = true) {
        if (!enabled) return
        if (view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) == true) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun longPress(haptic: HapticFeedback, enabled: Boolean = true) {
        if (!enabled) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    @SuppressLint("NewApi")
    private fun performExpressive(
        enabled: Boolean,
        haptic: HapticFeedback,
        view: View?,
        composeType: HapticFeedbackType,
        fallbackPlatformType: Int,
    ) {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            haptic.performHapticFeedback(composeType)
            return
        }
        if (view?.performHapticFeedback(fallbackPlatformType) == true) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}
