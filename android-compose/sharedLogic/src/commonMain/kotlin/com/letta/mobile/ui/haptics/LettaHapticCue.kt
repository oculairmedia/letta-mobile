package com.letta.mobile.ui.haptics

/**
 * Platform-neutral haptic vocabulary. Feature UI should describe intent with
 * these cues instead of choosing Android or iOS haptic APIs directly.
 */
enum class LettaHapticCue(
    val playback: LettaHapticPlayback,
    /** Accompanies an animation; [HapticPolicy] drops it under reduced motion. */
    val motionCoupled: Boolean = false,
    /** Per-cue rate floor enforced by [HapticPolicy]; 0 means only the global floor applies. */
    val minIntervalMillis: Int = 0,
) {
    Confirm(LettaHapticPlayback.PlatformFeedback),
    Reject(LettaHapticPlayback.PlatformFeedback),
    ToggleOn(LettaHapticPlayback.PlatformFeedback),
    ToggleOff(LettaHapticPlayback.PlatformFeedback),
    SegmentTick(LettaHapticPlayback.PlatformFeedback),
    SegmentFrequentTick(LettaHapticPlayback.PlatformFeedback, minIntervalMillis = 40),
    GestureThreshold(LettaHapticPlayback.PlatformFeedback),
    GestureStart(LettaHapticPlayback.PlatformFeedback),
    GestureEnd(LettaHapticPlayback.PlatformFeedback),
    ContextClick(LettaHapticPlayback.PlatformFeedback),
    LongPress(LettaHapticPlayback.PlatformFeedback),
    ReorderSwapTick(LettaHapticPlayback.PlatformFeedback),
    ReorderDragStart(LettaHapticPlayback.PlatformFeedback),
    ReorderDragEnd(LettaHapticPlayback.PlatformFeedback),
    StreamingStart(LettaHapticPlayback.Pattern),
    StreamingPulse(LettaHapticPlayback.Pattern),
    StreamingComplete(LettaHapticPlayback.Pattern),
    ToolCallStarted(LettaHapticPlayback.Pattern),
    ToolCallSucceeded(LettaHapticPlayback.Pattern),
    ToolCallFailed(LettaHapticPlayback.Pattern),

    // Product-feel cues (letta-mobile-86njl.8). Playback is realised per platform by the actual
    // (letta-mobile-86njl.9). Error, success, selection and chat-head moments reuse the cues
    // above (Reject, Confirm, SegmentFrequentTick, GestureStart/GestureEnd).
    SendLaunch(LettaHapticPlayback.PlatformFeedback, motionCoupled = true),
    SendLand(LettaHapticPlayback.PlatformFeedback, motionCoupled = true),
    ReplyArrived(LettaHapticPlayback.PlatformFeedback),
    RunFinished(LettaHapticPlayback.PlatformFeedback),
    RunFailed(LettaHapticPlayback.PlatformFeedback),
    ApprovalNeeded(LettaHapticPlayback.PlatformFeedback),
    ApprovalDecided(LettaHapticPlayback.PlatformFeedback),
    ScrollToLatest(LettaHapticPlayback.PlatformFeedback),
    OverscrollBounce(LettaHapticPlayback.PlatformFeedback, motionCoupled = true),
    Snap(LettaHapticPlayback.PlatformFeedback, minIntervalMillis = 80),
    Undo(LettaHapticPlayback.PlatformFeedback),
    Redo(LettaHapticPlayback.PlatformFeedback),
    Erase(LettaHapticPlayback.PlatformFeedback, minIntervalMillis = 120),
    ZoomLimit(LettaHapticPlayback.PlatformFeedback),
    Create(LettaHapticPlayback.PlatformFeedback),
    MicStop(LettaHapticPlayback.PlatformFeedback),
    Navigate(LettaHapticPlayback.PlatformFeedback),
    DestructiveConfirm(LettaHapticPlayback.PlatformFeedback),
    MascotTap(LettaHapticPlayback.PlatformFeedback),
}

enum class LettaHapticPlayback {
    PlatformFeedback,
    Pattern,
}

enum class LettaHapticIntensity(val value: Float) {
    Feather(0.12f),
    Light(0.25f),
    Medium(0.5f),
    Strong(0.75f),
    High(1f),
}

data class LettaHapticPulse(
    val startTimeMs: Long,
    val durationMs: Long,
    val intensity: LettaHapticIntensity = LettaHapticIntensity.Medium,
) {
    init {
        require(startTimeMs >= 0L) { "startTimeMs must be non-negative." }
        require(durationMs > 0L) { "durationMs must be positive." }
    }
}

data class LettaHapticPattern(
    val pulses: List<LettaHapticPulse>,
) {
    init {
        require(pulses == pulses.sortedBy { it.startTimeMs }) { "pulses must be sorted by start time." }
    }

    val totalDurationMs: Long
        get() = pulses.maxOfOrNull { it.startTimeMs + it.durationMs } ?: 0L

    companion object {
        val Empty = LettaHapticPattern(emptyList())
    }
}

object LettaHapticPatterns {
    fun patternFor(cue: LettaHapticCue): LettaHapticPattern? = when (cue) {
        LettaHapticCue.StreamingStart -> LettaHapticPattern(
            pulses = listOf(
                LettaHapticPulse(startTimeMs = 0L, durationMs = 10L, intensity = LettaHapticIntensity.Light),
            ),
        )
        LettaHapticCue.StreamingPulse -> LettaHapticPattern(
            pulses = listOf(
                LettaHapticPulse(startTimeMs = 0L, durationMs = 2L, intensity = LettaHapticIntensity.Feather),
            ),
        )
        LettaHapticCue.StreamingComplete -> LettaHapticPattern(
            pulses = listOf(
                LettaHapticPulse(startTimeMs = 0L, durationMs = 14L, intensity = LettaHapticIntensity.Light),
            ),
        )
        LettaHapticCue.ToolCallStarted -> LettaHapticPattern(
            pulses = listOf(
                LettaHapticPulse(startTimeMs = 0L, durationMs = 8L, intensity = LettaHapticIntensity.Light),
                LettaHapticPulse(startTimeMs = 44L, durationMs = 8L, intensity = LettaHapticIntensity.Light),
            ),
        )
        LettaHapticCue.ToolCallSucceeded -> LettaHapticPattern(
            pulses = listOf(
                LettaHapticPulse(startTimeMs = 0L, durationMs = 12L, intensity = LettaHapticIntensity.Medium),
            ),
        )
        LettaHapticCue.ToolCallFailed -> LettaHapticPattern(
            pulses = listOf(
                LettaHapticPulse(startTimeMs = 0L, durationMs = 16L, intensity = LettaHapticIntensity.Medium),
                LettaHapticPulse(startTimeMs = 58L, durationMs = 18L, intensity = LettaHapticIntensity.Medium),
            ),
        )
        else -> null
    }
}
