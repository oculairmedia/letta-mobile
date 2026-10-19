package com.letta.mobile.data.chat.branch

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage

/** The conversation a branch is taken from, and the timeline the page shows for it (oldest first). */
data class BranchSource(
    val conversationId: String,
    val agentId: String?,
    val timeline: List<UiMessage> = emptyList(),
    /** False while older history is not loaded, so the first loaded message may not be the first. */
    val historyComplete: Boolean = true,
)

/** What a branch puts back in the new conversation's composer: the prompt's text and its images. */
data class BranchDraft(val text: String, val images: List<MessageContentPart.Image> = emptyList()) {
    companion object {
        fun of(message: UiMessage): BranchDraft = BranchDraft(
            text = message.content,
            images = message.attachments.filter { it.base64.isNotEmpty() }.map { MessageContentPart.Image(it.base64, it.mediaType) },
        )
    }
}

/** An "Edit and resend" (or rerun) branch: the new conversation and the prompt to put back in its composer. */
data class EditBranch(val conversation: Conversation, val prompt: BranchDraft) {
    /** The prompt's text, for callers that only edit text. */
    val draft: String get() = prompt.text
}

/** A fork or edit that cannot run, with a message fit to show the user. */
class BranchUnavailableException(message: String) : IllegalStateException(message)

/** How [ConversationBranching] reaches the backend. */
data class BranchBackend(
    val fork: suspend (ConversationForkRequest) -> Conversation,
    /** Creates an empty conversation of the agent (the edit of a conversation's first prompt). */
    val createEmpty: suspend (agentId: String) -> Conversation,
    /**
     * The conversation's stored messages, oldest first, read from the server. When present it is
     * the authority on where a branch starts: its ids are the ones `conversation_fork` accepts,
     * where a timeline row may still carry a streamed or optimistic id. Null when the backend
     * cannot list them.
     */
    val history: (suspend (conversationId: String) -> BranchHistory)? = null,
    /** Names a conversation (its `summary`); null when the backend cannot, so a named fork keeps the source's title. */
    val rename: (suspend (conversation: Conversation, name: String) -> Conversation)? = null,
)

/** Stored messages read from the server, oldest first; [complete] when nothing older was left out. */
data class BranchHistory(val messages: List<BranchMessage>, val complete: Boolean)

/**
 *  /.16, forks a conversation from a message (optionally
 * naming the fork), "Edit and resend" (fork before the prompt, then hand the prompt back to the
 * composer), and "Rerun" (the same fork, with the prompt sent again unchanged). Shared by both
 * clients; each binds a [BranchBackend] to its gateway or repository, and
 * [ConversationBranchController] opens the result.
 *
 * The original conversation is never written: a fork copies, and an edit never touches the prompt
 * it replaces.
 */
class ConversationBranching(private val backend: BranchBackend) {

    /** True when a fork can be named (the backend can rename a conversation). */
    val canName: Boolean get() = backend.rename != null

    /** Forks [source] so the new conversation keeps [target]'s exchange (see [ConversationBranchPlanner.forkPoint]). */
    suspend fun forkFrom(source: BranchSource, target: UiMessage, name: String? = null): Conversation {
        if (!ConversationBranchPlanner.canFork(target)) throw BranchUnavailableException(NOT_STORED)
        val point = place(source, target) { messages, wanted, _, fromEnd ->
            ConversationBranchPlanner.forkPoint(messages, wanted, fromEnd)
        } ?: fallbackForkPoint(target)
        return named(branchAt(source, point), name)
    }

    /** Forks all of [source] (the drawer's "Fork"), optionally naming the fork. */
    suspend fun forkConversation(source: BranchSource, name: String? = null): Conversation =
        named(backend.fork(ConversationForkRequest(source.conversationId, source.agentId)), name)

    /**
     * Forks [source] just before [target] and returns the fork with [target]'s prompt as the draft.
     * The caller opens the fork and puts the draft in its composer; sending it completes the edit.
     */
    suspend fun editAndResend(source: BranchSource, target: UiMessage): EditBranch {
        if (!ConversationBranchPlanner.canEdit(target)) throw BranchUnavailableException(NOT_STORED)
        return EditBranch(branchBefore(source, target), BranchDraft.of(target))
    }

    /**
     * Forks [source] just before [target] so its prompt can be sent again unchanged: the new
     * conversation answers it afresh while the original keeps its answer.
     */
    suspend fun rerun(source: BranchSource, target: UiMessage): EditBranch {
        if (!ConversationBranchPlanner.canRerun(target)) throw BranchUnavailableException(NOT_STORED)
        return EditBranch(branchBefore(source, target), BranchDraft.of(target))
    }

    private suspend fun branchBefore(source: BranchSource, target: UiMessage): Conversation {
        val point = place(source, target) { messages, wanted, complete, fromEnd ->
            ConversationBranchPlanner.editPoint(messages, wanted, complete, fromEnd)
        } ?: throw BranchUnavailableException(LOAD_OLDER)
        return branchAt(source, point)
    }

    /**
     * Plans on the server's list first (its ids are fork points upstream accepts), then on the
     * page's timeline. The timeline still locates the target: a row known by a streamed id is found
     * in the server's list by its text, counted back from the newest.
     */
    private suspend fun place(
        source: BranchSource,
        target: UiMessage,
        plan: (List<BranchMessage>, BranchMessage, Boolean, Int) -> BranchPoint?,
    ): BranchPoint? {
        val wanted = BranchMessage.of(target)
        val onPage = source.timeline.map(BranchMessage::of)
        val fromEnd = sameTextAfter(onPage, wanted)
        val server = backend.history?.invoke(source.conversationId)
        return server?.let { plan(it.messages, wanted, it.complete, fromEnd) }
            // Without the server's list, the page's flag is all there is to tell a first prompt.
            ?: plan(onPage, wanted, server == null && source.historyComplete, 0)
    }

    /** A reply the backend cannot list is forked through its own id, when that id is a server id. */
    private fun fallbackForkPoint(target: UiMessage): BranchPoint {
        if (!BranchIds.isServerId(target)) throw BranchUnavailableException(NOT_SAVED_YET)
        return BranchPoint.Through(target.id)
    }

    private suspend fun named(fork: Conversation, name: String?): Conversation {
        val title = name?.trim()?.takeIf { it.isNotEmpty() } ?: return fork
        val rename = backend.rename ?: return fork
        return rename(fork, title)
    }

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
        const val NOT_SAVED_YET = "This message is still being saved. Try again in a moment."
        const val LOAD_OLDER = "Load the earlier messages first, then edit this one."
        const val NO_AGENT = "This conversation's agent is unknown, so it can't start a new branch."

        /** How many messages after [target] on the page read the same, so a text match counts back past them. */
        private fun sameTextAfter(page: List<BranchMessage>, target: BranchMessage): Int {
            val index = page.indexOfFirst { it.matches(target) }
            if (index < 0 || target.text.isNullOrBlank()) return 0
            return page.subList(index + 1, page.size).count { it.isPrompt == target.isPrompt && it.text?.trim() == target.text.trim() }
        }
    }
}
