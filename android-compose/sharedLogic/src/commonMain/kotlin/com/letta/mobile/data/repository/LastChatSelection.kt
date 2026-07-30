package com.letta.mobile.data.repository

data class LastChatSelection(
    val agentId: String,
    val agentName: String? = null,
    val conversationId: String? = null,
)

/**
 * Platform-neutral merge policy for the persisted chat selection.
 *
 * letta-mobile-etib9: the chat screen writes the selection on entry using
 * whatever the navigation argument carried, which is blank on a cold start
 * (before the roster resolves a name). Treating that blank as a "clear" erased
 * the stored agent name; the next launch then rebuilt the start destination
 * from the now-nameless selection and re-seeded a blank name, so once the name
 * was lost it never came back on its own.
 *
 * Rules:
 *  - A blank/absent [agentName] is a no-op, not a clear: carry the previously
 *    resolved name forward.
 *  - Carry forward ONLY when [agentId] is unchanged. Reusing the previous
 *    agent's name for a different agent renders a confidently *wrong* name,
 *    which is worse than an empty one.
 *  - A blank [agentId] is not a valid selection and yields `null`.
 *
 * Lives in `commonMain` so the production repository and every test substitute
 * share one implementation instead of drifting (AGENTS.md: state transforms are
 * must-be-shared).
 */
fun mergeLastChatSelection(
    previous: LastChatSelection?,
    agentId: String,
    agentName: String?,
    conversationId: String?,
): LastChatSelection? {
    val normalizedAgentId = agentId.takeIf { it.isNotBlank() } ?: return null
    val carriedName = previous
        ?.takeIf { it.agentId == normalizedAgentId }
        ?.agentName
    return LastChatSelection(
        agentId = normalizedAgentId,
        agentName = agentName?.takeIf { it.isNotBlank() } ?: carriedName,
        conversationId = conversationId?.takeIf { it.isNotBlank() },
    )
}
