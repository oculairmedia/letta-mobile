package com.letta.mobile.data.chat.branch

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.UiMessage

/** The conversation a branch is taken from, and the timeline the page shows for it (oldest first). */
data class BranchSource(
    val conversationId: String,
    val agentId: String?,
    val timeline: List<UiMessage> = emptyList(),
    /** False while older history is not loaded, so the first loaded message may not be the first. */
    val historyComplete: Boolean = true,
)

/** An "Edit and resend" branch: the new conversation and the text to put back in its composer. */
data class EditBranch(val conversation: Conversation, val draft: String)

/** A fork or edit that cannot run, with a message fit to show the user. */
class BranchUnavailableException(message: String) : IllegalStateException(message)

/** How [ConversationBranching] reaches the backend. */
data class BranchBackend(
    val fork: suspend (ConversationForkRequest) -> Conversation,
    /** Creates an empty conversation of the agent (the edit of a conversation's first prompt). */
    val createEmpty: suspend (agentId: String) -> Conversation,
    /**
     * The conversation's stored messages, oldest first, read from the server; used when the page's
     * timeline does not hold the message (a paged timeline the page renders elsewhere). Null when
     * the backend cannot list them.
     */
    val history: (suspend (conversationId: String) -> BranchHistory)? = null,
)

/** Stored messages read from the server, oldest first; [complete] when nothing older was left out. */
data class BranchHistory(val messages: List<BranchMessage>, val complete: Boolean)

/**
 * letta-mobile-bzvro.15 / .16 (F15, F16): forks a conversation from a message, and "Edit and
 * resend" (fork before the prompt, then hand its text back to the composer). Shared by both
 * clients; each binds a [BranchBackend] to its gateway or repository, then opens the returned
 * conversation itself.
 *
 * The original conversation is never written: a fork copies, and an edit never touches the prompt
 * it replaces.
 */
class ConversationBranching(private val backend: BranchBackend) {

    /** Forks [source] so the new conversation keeps [target]'s exchange (see [ConversationBranchPlanner.forkPoint]). */
    suspend fun forkFrom(source: BranchSource, target: UiMessage): Conversation {
        if (!ConversationBranchPlanner.canFork(target)) throw BranchUnavailableException(NOT_STORED)
        val wanted = BranchMessage.of(target)
        val point = ConversationBranchPlanner.forkPoint(source.timeline.map(BranchMessage::of), wanted)
            ?: serverHistory(source)?.let { ConversationBranchPlanner.forkPoint(it.messages, wanted) }
            ?: BranchPoint.Through(target.id)
        return branchAt(source, point)
    }

    /**
     * Forks [source] just before [target] and returns the fork with [target]'s text as the draft.
     * The caller opens the fork and puts the draft in its composer; sending it completes the edit.
     */
    suspend fun editAndResend(source: BranchSource, target: UiMessage): EditBranch {
        if (!ConversationBranchPlanner.canEdit(target)) throw BranchUnavailableException(NOT_STORED)
        val wanted = BranchMessage.of(target)
        val point = ConversationBranchPlanner.editPoint(source.timeline.map(BranchMessage::of), wanted, source.historyComplete)
            ?: serverHistory(source)?.let { ConversationBranchPlanner.editPoint(it.messages, wanted, it.complete) }
            ?: throw BranchUnavailableException(LOAD_OLDER)
        return EditBranch(branchAt(source, point), target.content)
    }

    private suspend fun serverHistory(source: BranchSource): BranchHistory? =
        backend.history?.invoke(source.conversationId)

    private suspend fun branchAt(source: BranchSource, point: BranchPoint): Conversation = when (point) {
        is BranchPoint.Through -> backend.fork(
            ConversationForkRequest(
                conversationId = source.conversationId,
                agentId = source.agentId,
                throughMessageId = point.messageId,
            ),
        )
        BranchPoint.Empty -> backend.createEmpty(source.agentId ?: throw BranchUnavailableException(NO_AGENT))
    }

    companion object {
        const val NOT_STORED = "This message isn't saved yet, so the conversation can't branch from it."
        const val LOAD_OLDER = "Load the earlier messages first, then edit this one."
        const val NO_AGENT = "This conversation's agent is unknown, so it can't start a new branch."
    }
}
