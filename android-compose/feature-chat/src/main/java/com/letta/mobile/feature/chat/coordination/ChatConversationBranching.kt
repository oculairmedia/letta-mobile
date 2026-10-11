package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.chat.branch.BranchBackend
import com.letta.mobile.data.chat.branch.BranchHistory
import com.letta.mobile.data.chat.branch.BranchOpener
import com.letta.mobile.data.chat.branch.BranchSource
import com.letta.mobile.data.chat.branch.ConversationBranchController
import com.letta.mobile.data.chat.branch.ConversationBranching
import com.letta.mobile.data.chat.branch.PendingComposerDrafts
import com.letta.mobile.data.chat.branch.PendingDraft
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.coroutines.CoroutineScope

/**
 * letta-mobile-bzvro.15 / .16, letta-mobile-7mys7: Android's binding of the shared
 * [ConversationBranchController]. Forks go through [IConversationRepository.forkConversation],
 * which routes by backend (App Server and Iroh hosts fork with `conversation_fork`, REST with
 * `/fork`). The fork opens as a new chat route, so an edit's or rerun's prompt crosses the
 * navigation through [PendingComposerDrafts].
 */
class ChatConversationBranching(
    scope: CoroutineScope,
    conversations: IConversationRepository,
    onError: (String) -> Unit,
    history: (suspend (conversationId: String) -> BranchHistory)? = null,
    private val drafts: PendingComposerDrafts = PendingComposerDrafts.shared,
) {
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
            history = history,
        ),
    )

    /** Where the fork opens; set per request, since the page's navigation is bound at the call. */
    private var open: (agentId: String, conversationId: String) -> Unit = { _, _ -> }

    private val controller = ConversationBranchController(
        scope = scope,
        branching = { branching },
        opener = BranchOpener { outcome ->
            outcome.draft?.let { drafts.put(outcome.conversation.id.value, PendingDraft(it, outcome.sendNow)) }
            open(outcome.conversation.agentId.value.ifBlank { outcome.source.agentId.orEmpty() }, outcome.conversation.id.value)
        },
        onError = onError,
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

    fun forkFrom(origin: Origin, message: UiMessage) = start(origin) { controller.fork(origin.source, message) }

    fun editAndResend(origin: Origin, message: UiMessage) = start(origin) { controller.editAndResend(origin.source, message) }

    fun rerun(origin: Origin, message: UiMessage) = start(origin) { controller.rerun(origin.source, message) }

    private fun start(origin: Origin, request: () -> Boolean) {
        open = origin.open
        request()
    }
}
