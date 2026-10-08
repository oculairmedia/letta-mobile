package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.chat.branch.BranchBackend
import com.letta.mobile.data.chat.branch.BranchOpener
import com.letta.mobile.data.chat.branch.BranchSource
import com.letta.mobile.data.chat.branch.ConversationBranchRunner
import com.letta.mobile.data.chat.branch.ConversationBranching
import com.letta.mobile.data.chat.branch.PendingComposerDrafts
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.coroutines.CoroutineScope

/**
 * letta-mobile-bzvro.15 / .16 (F15, F16): Android's binding of the shared [ConversationBranching].
 * Forks go through [IConversationRepository.forkConversation], which routes by backend (App Server
 * and Iroh hosts fork with `conversation_fork`, REST with `/fork`). The fork opens as a new chat
 * route, so an edit's draft crosses the navigation through [PendingComposerDrafts].
 */
class ChatConversationBranching(
    scope: CoroutineScope,
    conversations: IConversationRepository,
    onError: (String) -> Unit,
    private val drafts: PendingComposerDrafts = PendingComposerDrafts.shared,
) {
    private val runner = ConversationBranchRunner(scope, onError)

    private val branching = ConversationBranching(
        BranchBackend(
            fork = { request ->
                conversations.forkConversation(
                    ConversationId(request.conversationId),
                    AgentId(requireNotNull(request.agentId) { ConversationBranching.NO_AGENT }),
                    request.throughMessageId,
                )
            },
            createEmpty = { agentId -> conversations.createConversation(AgentId(agentId)) },
        ),
    )

    /** The conversation a branch starts from and where the fork opens. */
    data class Origin(
        val conversationId: String,
        val agentId: String,
        val ui: ChatUiState,
        val open: (agentId: String, conversationId: String) -> Unit,
    ) {
        val source: BranchSource
            get() = BranchSource(conversationId, agentId, ui.messages, historyComplete = !ui.hasMoreOlderMessages)
    }

    fun forkFrom(origin: Origin, message: UiMessage) =
        runner.forkFrom(branching, origin.source, message, opener(origin))

    fun editAndResend(origin: Origin, message: UiMessage) =
        runner.editAndResend(branching, origin.source, message, opener(origin))

    private fun opener(origin: Origin) = BranchOpener { _, fork, draft ->
        draft?.let { drafts.put(fork.id.value, it) }
        origin.open(fork.agentId.value.ifBlank { origin.agentId }, fork.id.value)
    }
}
