package com.letta.mobile.ui.haptics

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import io.github.compose.jindong.core.executor.HapticExecutor
import io.github.compose.jindong.core.executor.createHapticExecutor
import io.github.compose.jindong.core.model.HapticIntensity as JindongHapticIntensity
import io.github.compose.jindong.core.model.HapticPattern as JindongHapticPattern
import io.github.compose.jindong.core.model.ScheduledHapticEvent

/**
 * Centralized semantic haptic vocabulary for Letta UI.
 *
 * Prefer the platform [View.performHapticFeedback] constants because it returns
 * whether the device accepted the effect. Compose's haptic bridge is still the
 * final fallback for old call sites, but routing modern devices through [View]
 * preserves the richer Pixel haptic vocabulary instead of collapsing everything
 * into the same generic Compose pulse. Expressive multi-pulse cues route through
 * Jindong so Android and future iOS backends can share the same Letta cue model.
 */
object HapticEffects {
    fun confirm(haptic: HapticFeedback, view: View? = null, enabled: Boolean = true) {
        perform(LettaHapticCue.Confirm, haptic, view, enabled)
    }

    fun reject(haptic: HapticFeedback, view: View? = null, enabled: Boolean = true) {
        perform(LettaHapticCue.Reject, haptic, view, enabled)
    }

    fun toggleOn(haptic: HapticFeedback, view: View? = null, enabled: Boolean = true) {
        perform(LettaHapticCue.ToggleOn, haptic, view, enabled)
    }

    fun toggleOff(haptic: HapticFeedback, view: View? = null, enabled: Boolean = true) {
        perform(LettaHapticCue.ToggleOff, haptic, view, enabled)
    }

    fun segmentTick(haptic: HapticFeedback, view: View? = null, enabled: Boolean = true) {
        perform(LettaHapticCue.SegmentTick, haptic, view, enabled)
    }

    fun segmentFrequentTick(haptic: HapticFeedback, view: View? = null, enabled: Boolean = true) {
        perform(LettaHapticCue.SegmentFrequentTick, haptic, view, enabled)
    }

    fun gestureThreshold(haptic: HapticFeedback, view: View? = null, enabled: Boolean = true) {
        perform(LettaHapticCue.GestureThreshold, haptic, view, enabled)
    }

    fun contextClick(haptic: HapticFeedback, view: View? = null, enabled: Boolean = true) {
        perform(LettaHapticCue.ContextClick, haptic, view, enabled)
    }

    fun longPress(haptic: HapticFeedback, view: View? = null, enabled: Boolean = true) {
        perform(LettaHapticCue.LongPress, haptic, view, enabled)
    }

    fun reorderSwapTick(view: View? = null, enabled: Boolean = true) {
        performViewOnly(LettaHapticCue.ReorderSwapTick, view, enabled)
    }

    fun reorderDragStart(view: View? = null, enabled: Boolean = true) {
        performViewOnly(LettaHapticCue.ReorderDragStart, view, enabled)
    }

    fun reorderDragEnd(view: View? = null, enabled: Boolean = true) {
        performViewOnly(LettaHapticCue.ReorderDragEnd, view, enabled)
    }

    fun streamingStart(view: View? = null, enabled: Boolean = true) {
        performPattern(LettaHapticCue.StreamingStart, view, enabled)
    }

    fun streamingPulse(view: View? = null, enabled: Boolean = true) {
        performPattern(LettaHapticCue.StreamingPulse, view, enabled)
    }

    fun streamingComplete(view: View? = null, enabled: Boolean = true) {
        performPattern(LettaHapticCue.StreamingComplete, view, enabled)
    }

    fun toolCallStarted(view: View? = null, enabled: Boolean = true) {
        performPattern(LettaHapticCue.ToolCallStarted, view, enabled)
    }

    fun toolCallSucceeded(view: View? = null, enabled: Boolean = true) {
        performPattern(LettaHapticCue.ToolCallSucceeded, view, enabled)
    }

    fun toolCallFailed(view: View? = null, enabled: Boolean = true) {
        performPattern(LettaHapticCue.ToolCallFailed, view, enabled)
    }

    fun perform(
        cue: LettaHapticCue,
        haptic: HapticFeedback,
        view: View? = null,
        enabled: Boolean = true,
    ) {
        if (!enabled) return
        if (cue.playback == LettaHapticPlayback.Pattern) {
            performPattern(cue, view, enabled = true)
            return
        }
        performPlatformCue(cue, haptic, view)
    }

    @SuppressLint("NewApi")
    private fun performViewOnly(cue: LettaHapticCue, view: View?, enabled: Boolean) {
        if (!enabled) return
        val spec = platformSpecFor(cue) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && spec.modernPlatformType != null) {
            if (performViewHaptic(view, spec.modernPlatformType)) return
        }
        performViewHaptic(view, spec.fallbackPlatformType)
    }

    private fun performPattern(cue: LettaHapticCue, view: View?, enabled: Boolean) {
        if (!enabled) return
        val pattern = LettaHapticPatterns.patternFor(cue) ?: return
        JindongHapticPatternPlayer.play(pattern, view)
    }

    @SuppressLint("NewApi")
    private fun performPlatformCue(
        cue: LettaHapticCue,
        haptic: HapticFeedback,
        view: View?,
    ) {
        val spec = platformSpecFor(cue) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && spec.modernPlatformType != null) {
            if (performViewHaptic(view, spec.modernPlatformType)) return
        }
        if (performViewHaptic(view, spec.fallbackPlatformType)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            haptic.performHapticFeedback(spec.composeType)
            return
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    @SuppressLint("NewApi")
    internal fun platformSpecFor(cue: LettaHapticCue): PlatformHapticSpec? = when (cue) {
        LettaHapticCue.Confirm -> PlatformHapticSpec(
            modernPlatformType = HapticFeedbackConstants.CONFIRM,
            fallbackPlatformType = HapticFeedbackConstants.CONTEXT_CLICK,
            composeType = HapticFeedbackType.Confirm,
        )
        LettaHapticCue.Reject -> PlatformHapticSpec(
            modernPlatformType = HapticFeedbackConstants.REJECT,
            fallbackPlatformType = HapticFeedbackConstants.LONG_PRESS,
            composeType = HapticFeedbackType.Reject,
        )
        LettaHapticCue.ToggleOn -> PlatformHapticSpec(
            modernPlatformType = HapticFeedbackConstants.TOGGLE_ON,
            fallbackPlatformType = HapticFeedbackConstants.CLOCK_TICK,
            composeType = HapticFeedbackType.ToggleOn,
        )
        LettaHapticCue.ToggleOff -> PlatformHapticSpec(
            modernPlatformType = HapticFeedbackConstants.TOGGLE_OFF,
            fallbackPlatformType = HapticFeedbackConstants.CLOCK_TICK,
            composeType = HapticFeedbackType.ToggleOff,
        )
        LettaHapticCue.SegmentTick -> PlatformHapticSpec(
            modernPlatformType = HapticFeedbackConstants.SEGMENT_TICK,
            fallbackPlatformType = HapticFeedbackConstants.CLOCK_TICK,
            composeType = HapticFeedbackType.SegmentTick,
        )
        LettaHapticCue.SegmentFrequentTick,
        LettaHapticCue.ReorderSwapTick,
        -> PlatformHapticSpec(
            modernPlatformType = HapticFeedbackConstants.SEGMENT_FREQUENT_TICK,
            fallbackPlatformType = HapticFeedbackConstants.CLOCK_TICK,
            composeType = HapticFeedbackType.SegmentTick,
        )
        LettaHapticCue.GestureThreshold -> PlatformHapticSpec(
            modernPlatformType = HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE,
            fallbackPlatformType = HapticFeedbackConstants.LONG_PRESS,
            composeType = HapticFeedbackType.GestureThresholdActivate,
        )
        LettaHapticCue.GestureStart,
        LettaHapticCue.ReorderDragStart,
        -> PlatformHapticSpec(
            modernPlatformType = HapticFeedbackConstants.GESTURE_START,
            fallbackPlatformType = HapticFeedbackConstants.LONG_PRESS,
            composeType = HapticFeedbackType.LongPress,
        )
        LettaHapticCue.GestureEnd,
        LettaHapticCue.ReorderDragEnd,
        -> PlatformHapticSpec(
            modernPlatformType = HapticFeedbackConstants.GESTURE_END,
            fallbackPlatformType = HapticFeedbackConstants.CLOCK_TICK,
            composeType = HapticFeedbackType.SegmentTick,
        )
        LettaHapticCue.ContextClick -> PlatformHapticSpec(
            modernPlatformType = null,
            fallbackPlatformType = HapticFeedbackConstants.CONTEXT_CLICK,
            composeType = HapticFeedbackType.LongPress,
        )
        LettaHapticCue.LongPress -> PlatformHapticSpec(
            modernPlatformType = null,
            fallbackPlatformType = HapticFeedbackConstants.LONG_PRESS,
            composeType = HapticFeedbackType.LongPress,
        )
        else -> null
    }

    private fun performViewHaptic(view: View?, feedbackConstant: Int): Boolean =
        view?.performHapticFeedback(feedbackConstant) == true
}

internal data class PlatformHapticSpec(
    val modernPlatformType: Int?,
    val fallbackPlatformType: Int,
    val composeType: HapticFeedbackType,
)

private object JindongHapticPatternPlayer {
    private var executorContext: Context? = null
    private var executor: HapticExecutor? = null

    fun play(pattern: LettaHapticPattern, view: View?): Boolean {
        if (pattern.pulses.isEmpty()) return false
        val context = view?.context?.applicationContext ?: return false
        val hapticExecutor = executorFor(context)
        if (!hapticExecutor.isSupported) return false
        hapticExecutor.executeAsync(pattern.toJindongPattern())
        return true
    }

    private fun executorFor(context: Context): HapticExecutor = synchronized(this) {
        val existing = executor
        if (existing != null && executorContext === context) return@synchronized existing
        existing?.release()
        createHapticExecutor(context).also {
            executorContext = context
            executor = it
        }
    }

    internal fun LettaHapticPattern.toJindongPattern(): JindongHapticPattern = JindongHapticPattern(
        events = pulses.map { pulse ->
            ScheduledHapticEvent(
                startTimeMs = pulse.startTimeMs,
                durationMs = pulse.durationMs,
                intensity = pulse.intensity.toJindongIntensity(),
            )
        },
    )

    private fun LettaHapticIntensity.toJindongIntensity(): JindongHapticIntensity = when (this) {
        LettaHapticIntensity.Feather,
        LettaHapticIntensity.Light,
        -> JindongHapticIntensity.LIGHT
        LettaHapticIntensity.Medium -> JindongHapticIntensity.MEDIUM
        LettaHapticIntensity.Strong -> JindongHapticIntensity.STRONG
        LettaHapticIntensity.High -> JindongHapticIntensity.HIGH
    }
}
