package com.letta.mobile.avatar.core

/**
 * The full presence vocabulary the director arbitrates and every surface/mod
 * observes (PRD §4 P2, §6 state matrix). One vocabulary across busts, docks,
 * the pet, rail orbs and mod-space, so nothing invents a second status system.
 *
 * Two families share this enum:
 *
 * **Behavior states** — the director *arbitrates* these from imperative input
 * signals ([AvatarDirector.setUserTyping], [AvatarDirector.setDragged], …) and
 * drives protocol commands for them per §6:
 * [IDLE], [LISTENING], [DRAGGED], [THINKING], [WORKING], [WAITING_INPUT], [SPEAKING],
 * [SUCCESS], [ERROR], [SLEEPING], [DREAMING].
 *
 * **Lifecycle states** — [LOADING], [FAILED], [DEGRADED] describe the avatar
 * *asset's* load status, not agent activity. They are fed in wholesale from the
 * runtime via [AvatarDirector.setLifecycle] and, when present, take over the
 * observable state (an avatar that failed to load has no behavior to express).
 * The director issues no behavior commands for them — per §6 their columns are
 * surface chrome (shimmer frames, orb fallback, capability-gated hiding) owned
 * by the surface host (P4), not the director. They live here so surfaces and
 * mods branch on a single [AvatarState] type.
 *
 * Arbitration priority, exactly as [AvatarDirector] resolves it: lifecycle
 * (`LOADING`/`FAILED`/`DEGRADED`) over everything, then
 * `DRAGGED > ERROR > SLEEPING > WAITING_INPUT > SPEAKING > SUCCESS > WORKING >
 * THINKING > LISTENING > DREAMING > IDLE`. [SLEEPING] gates every behavior below
 * it (you can always grab the pet, and a failure always surfaces); [DREAMING]
 * shows only when nothing in the foreground needs the mascot.
 */
enum class AvatarState {
    /** Nothing happening — neutral face, occasional blinks (§6 IDLE). */
    IDLE,

    /** The user is typing/composing — attentive, leans toward the caret. */
    LISTENING,

    /** The pet is being dragged by the user — spring-bones swing free. */
    DRAGGED,

    /** The agent is reasoning — relaxed, inward, amber. */
    THINKING,

    /**
     * The agent is executing a tool (or delegating to subagents) — amber, faster pulse.
     *
     * Held for at least [AvatarDirector.Config.workingMinDwellSeconds] and released only after a
     * quiet gap, so the reasoning/tool alternation inside one turn does not strobe the mascot.
     */
    WORKING,

    /** A tool approval is pending — surprised, steady amber, waiting on YOU. */
    WAITING_INPUT,

    /** The agent's reply is streaming/being spoken — mouth animates, green. */
    SPEAKING,

    /** A task just completed — happy flash, self-expires after its moment. */
    SUCCESS,

    /** Something went wrong — sad flash then settle, red. */
    ERROR,

    /** Quiet hours — sleep pose, eyes closed, attention suppressed. */
    SLEEPING,

    /**
     * A background dream (reflection / memory upkeep) is running and nothing in the foreground
     * needs the mascot (letta-mobile-iczm7). Distinct from quiet-hours [SLEEPING]: the agent is
     * busy on its own, not off. Eyes closed, attention suppressed, like sleeping.
     */
    DREAMING,

    /** Lifecycle: the avatar asset is being fetched/parsed (surface chrome). */
    LOADING,

    /** Lifecycle: the avatar failed to load — surfaces fall back to the orb. */
    FAILED,

    /** Lifecycle: the avatar loaded but lacks capabilities (e.g. no expressions). */
    DEGRADED,
    ;

    /** Asleep, for any reason: eyes held closed, no blink loop, no attention to the pointer. */
    val eyesClosed: Boolean get() = this == SLEEPING || this == DREAMING
}

/**
 * Avatar-asset load status fed to [AvatarDirector.setLifecycle]. Mirrors the
 * lifecycle triplet of [AvatarState] plus [NONE] for "no lifecycle override —
 * let behavior states show through". Kept separate from [AvatarState] so the
 * feed API can't be handed a behavior state by mistake.
 */
enum class AvatarLifecycle {
    /** Avatar is loaded and healthy — behavior states are observable. */
    NONE,

    /** Avatar asset is loading — maps to [AvatarState.LOADING]. */
    LOADING,

    /** Avatar failed to load — maps to [AvatarState.FAILED]. */
    FAILED,

    /** Avatar loaded but degraded (missing capabilities) — [AvatarState.DEGRADED]. */
    DEGRADED,
}
