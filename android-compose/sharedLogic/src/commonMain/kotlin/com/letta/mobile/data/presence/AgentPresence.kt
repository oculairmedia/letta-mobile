package com.letta.mobile.data.presence

/**
 * What an agent is doing right now, as the UI can tell. Feeds the avatar director on every platform.
 *
 * WORKING and DELEGATING are the honest middle of a turn: before they existed, running a tool and
 * spawning subagents both read as THINKING, so the mascot was less informative than the timeline
 * beside it.
 */
enum class AgentActivityKind { IDLE, THINKING, WORKING, DELEGATING, SPEAKING }

/**
 * One agent's presence. Platform-neutral on purpose: the avatar director (avatar/core) turns this
 * into a mascot state with its own arbitration and timing; surfaces never map it themselves.
 */
data class AgentPresence(
    val activity: AgentActivityKind = AgentActivityKind.IDLE,
    /** A tool approval is parked in one of this agent's conversations. */
    val awaitingApproval: Boolean = false,
    /** The user is composing to this agent. */
    val userTyping: Boolean = false,
    /** The last attempt in one of this agent's conversations failed. */
    val error: Boolean = false,
    /** The tool in flight while [activity] is WORKING/DELEGATING, for surfaces that name it. */
    val toolName: String? = null,
    /**
     * A background dream (reflection) or memory-upkeep run is in flight for
     * this agent. It is not a turn, so it never changes [activity]; the mascot's director decides
     * when an otherwise quiet agent shows it.
     */
    val dreaming: Boolean = false,
    /**
     * The moments across this agent's conversations (sends, tool starts and
     * ends, approvals, finishes, failures) as counts, so a reader that samples presence still sees
     * each one. Summed over conversations; a conversation that is cleared takes its counts with it.
     */
    val edges: RunEdges = RunEdges.NONE,
) {
    companion object {
        val IDLE = AgentPresence()
    }
}
