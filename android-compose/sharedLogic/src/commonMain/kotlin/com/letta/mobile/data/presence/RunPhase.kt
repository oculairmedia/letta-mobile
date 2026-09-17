package com.letta.mobile.data.presence

/**
 * What a conversation's turn is actually doing right now — one phase at a time, and a turn is a
 * sequence of them.
 *
 * Before this existed the whole app saw two booleans (running / streamingTokens), so "reasoning",
 * "running a tool" and "waiting for the user to approve a tool" all read as one undifferentiated
 * THINKING. The runtime has always known the difference; [RunPhaseReducer] is what carries it.
 */
enum class RunPhase {
    /** No turn in flight. */
    IDLE,

    /** Sent, or between phases: no tokens and no tool in flight yet — the thinking gap. */
    QUEUED,

    /** Reasoning tokens are streaming. */
    REASONING,

    /** A tool call is in flight (from its call until its return). */
    WORKING,

    /** [WORKING] where the tool spawned subagents that are running. */
    DELEGATING,

    /** Assistant tokens are streaming. */
    RESPONDING,

    /** An approval was requested and is not resolved — the turn is parked on the user. */
    AWAITING_INPUT,

    /** Cancel requested, terminal not yet seen. */
    INTERRUPTING,

    /** Terminal with an error, attributed to this conversation. */
    FAILED,

    /** Terminal, ok. Momentary: the next event of any kind returns the conversation to [IDLE]. */
    DONE,

    ;

    /** A turn is in flight: sent and not yet at its terminal frame. */
    val isBusy: Boolean
        get() = this in BUSY

    /** A tool is executing for this conversation. */
    val isToolRunning: Boolean
        get() = this == WORKING || this == DELEGATING

    companion object {
        private val BUSY = setOf(QUEUED, REASONING, WORKING, DELEGATING, RESPONDING, AWAITING_INPUT, INTERRUPTING)
    }
}
