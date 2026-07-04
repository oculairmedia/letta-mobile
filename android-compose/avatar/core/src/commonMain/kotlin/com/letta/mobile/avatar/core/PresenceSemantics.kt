package com.letta.mobile.avatar.core

/**
 * Presence semantics service (PRD §4 P3) — the single source of truth mapping a
 * director [AvatarState] to the ThinkingRing presence language `{color, mode}`.
 *
 * Every surface that shows presence — rail orb ThinkingRing, bust frame stroke,
 * dock status card, stage name chips, pet floor-glow, pet status-chip dot —
 * consumes this instead of computing its own colors, so the whole app speaks
 * one status language (§2 goal: "the avatar never invents a second status
 * system").
 *
 * Dependency-free by construction: color is an ARGB token (a plain [Long]), not
 * a UI `Color`, so this stays in commonMain with no graphics dependency. Each
 * surface converts the token to its own color type at the edge.
 */
object PresenceSemantics {
    /**
     * Presence ring color tokens as ARGB `0xAARRGGBB` [Long] values. These are
     * the canonical hex values shared with the desktop ThinkingRing palette;
     * this service is the one source those surfaces should read from as they are
     * wired up (desktop wiring is a later slice).
     */
    object Color {
        /** Working / thinking. Matches ThinkingRing amber `#E0A33E`. */
        const val AMBER: Long = 0xFFE0A33E

        /** Firing / speaking / success. Matches ThinkingRing green `#34C759`. */
        const val GREEN: Long = 0xFF34C759

        /** Error. Matches ThinkingRing red `#E5484D`. */
        const val RED: Long = 0xFFE5484D
    }

    /** How the presence ring animates for a state. */
    enum class Mode {
        /** Ring hidden — no active presence to show. */
        NONE,

        /** Solid steady ring — "waiting on you" / speaking. */
        STEADY,

        /** Slow ease pulse — "working". THINKING uses a 1.2s ease. */
        PULSE,

        /** One-shot flash then off — SUCCESS uses 0.6s. */
        FLASH,
    }

    /** Default cross-surface ring transition duration (§6 global: 250ms). */
    const val TRANSITION_MILLIS: Int = 250

    /** THINKING pulse period (§6: amber pulse 1.2s ease). */
    const val PULSE_MILLIS: Int = 1_200

    /** SUCCESS flash duration (§6/§4 P3: green flash 0.6s). */
    const val FLASH_MILLIS: Int = 600

    /**
     * The presence cue for [state]. Total over all 12 [AvatarState] values:
     * behavior states map per §4 P3; every other state (IDLE, LISTENING,
     * SLEEPING, DRAGGED and the lifecycle states) shows no ring — presence is a
     * *status* signal, and those states carry no run status.
     */
    fun cueFor(state: AvatarState): PresenceCue = when (state) {
        AvatarState.THINKING -> PresenceCue(Color.AMBER, Mode.PULSE, PULSE_MILLIS)
        AvatarState.WAITING_INPUT -> PresenceCue(Color.AMBER, Mode.STEADY)
        AvatarState.SPEAKING -> PresenceCue(Color.GREEN, Mode.STEADY)
        AvatarState.SUCCESS -> PresenceCue(Color.GREEN, Mode.FLASH, FLASH_MILLIS)
        AvatarState.ERROR -> PresenceCue(Color.RED, Mode.STEADY)
        AvatarState.IDLE,
        AvatarState.LISTENING,
        AvatarState.DRAGGED,
        AvatarState.SLEEPING,
        AvatarState.LOADING,
        AvatarState.FAILED,
        AvatarState.DEGRADED,
        -> PresenceCue.OFF
    }
}

/**
 * A presence signal: which [color] the ring shows and how it [mode]-animates.
 * [durationMillis] is the mode's own timing (pulse period / flash length);
 * `null` for [PresenceSemantics.Mode.NONE] and [PresenceSemantics.Mode.STEADY],
 * which have no intrinsic duration.
 *
 * [color] is meaningful only when [mode] is not [PresenceSemantics.Mode.NONE];
 * for [PresenceSemantics.Mode.NONE] it is left at [color] `0` (fully
 * transparent) so a naive consumer that ignores [mode] still renders nothing.
 */
data class PresenceCue(
    /** ARGB `0xAARRGGBB` token (see [PresenceSemantics.Color]); `0` when off. */
    val color: Long,
    val mode: PresenceSemantics.Mode,
    val durationMillis: Int? = null,
) {
    /** True when the ring should be shown at all. */
    val isVisible: Boolean get() = mode != PresenceSemantics.Mode.NONE

    companion object {
        /** No ring — the state carries no run status. */
        val OFF = PresenceCue(0L, PresenceSemantics.Mode.NONE)
    }
}
