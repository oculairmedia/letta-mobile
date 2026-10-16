package com.letta.mobile.avatar.core

/**
 * A one-shot the mascot plays and returns from, as opposed to the sustained [AvatarState] it sits
 * in (letta-mobile-te3n1). The [MascotDirector] decides which fire - with priorities, cooldowns
 * and suppression - and every renderer receives them through [AvatarRuntime.react]; a renderer
 * whose asset has no animation for one plays its fallback or nothing.
 */
enum class AvatarReaction {
    /** The user sent a message: a small nod. */
    SENT,

    /** A tool started running. Rate-limited so a storm of tools reads as one beat. */
    TOOL_START,

    /** A tool returned. Rate-limited like [TOOL_START]. */
    TOOL_DONE,

    /** The turn asked the user to approve a tool; the sustained state is WAITING_INPUT. */
    APPROVAL_ASK,

    /** A run completed: the happy hop. Fires for every completion, back to back included. */
    RUN_DONE,

    /** A run failed: the error flash before the sustained ERROR settle. */
    RUN_FAILED,

    /** The user poked the mascot. */
    POKE,

    /** Poked too often too fast. */
    DIZZY,

    /** Put down after a drag; the argument is the impact 0..1. */
    LAND,

    /** Woken from sleeping or dreaming, before whatever woke it. */
    WAKE,

    /** Dozing off into sleeping or dreaming. */
    YAWN,

    /** A double-click easter egg; the argument picks the trick. */
    TRICK,
}
