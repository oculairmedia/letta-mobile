package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.chat.branch.BranchBackend
import com.letta.mobile.data.chat.branch.BranchHistory
import com.letta.mobile.data.chat.branch.BranchOpener
import com.letta.mobile.data.chat.branch.BranchSource
import com.letta.mobile.data.chat.branch.ConversationBranchController
import com.letta.mobile.data.chat.branch.ConversationBranching
import com.letta.mobile.data.chat.branch.ConversationLineage
import com.letta.mobile.data.chat.branch.PendingComposerDrafts
import com.letta.mobile.data.chat.branch.PendingDraft
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.ui.chat.session.ChatBranchSupport
import com.letta.mobile.ui.chat.session.ChatBranchView
import com.letta.mobile.ui.chat.session.ChatBranchingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Android lifecycle binding to the shared branch controller, with request-bound navigation. */
class ChatConversationBranching(
    scope: CoroutineScope,
    conversations: IConversationRepository,
    onError: (String) -> Unit,
    history: (suspend (conversationId: String) -> BranchHistory)? = null,
    private val drafts: PendingComposerDrafts = PendingComposerDrafts.shared,
    /** letta-mobile-7mys7 (G37): where a fork records its parent, for the drawer's "Forked from". */
    lineage: ConversationLineage? = null,
) {
    private var accepts: () -> Boolean = { true }
    private val branching = ConversationBranching(
        BranchBackend(
            fork = { request ->
                if (!accepts()) throw kotlinx.coroutines.CancellationException("Chat focus superseded")
                conversations.forkConversation(
                    ConversationId(request.conversationId),
                    AgentId(requireNotNull(request.agentId) { ConversationBranching.NO_AGENT }),
                    request.throughMessageId,
                )
            },
            createEmpty = { agentId ->
                if (!accepts()) throw kotlinx.coroutines.CancellationException("Chat focus superseded")
                conversations.createConversation(AgentId(agentId))
            },
            history = history,
        ),
    )

    /** Where the fork opens; set per request, since the page's navigation is bound at the call. */
    private var open: (agentId: String, conversationId: String) -> Unit = { _, _ -> }

    private val controller = ConversationBranchController(
        scope = scope,
        branching = { branching },
        opener = BranchOpener { outcome ->
            if (!accepts()) return@BranchOpener
            outcome.draft?.let { drafts.put(outcome.conversation.id.value, PendingDraft(it, outcome.sendNow)) }
            open(outcome.conversation.agentId.value.ifBlank { outcome.source.agentId.orEmpty() }, outcome.conversation.id.value)
        },
        lineage = lineage,
        onError = onError,
    )

    /** The conversation a branch starts from and where the fork opens. */
    data class Origin(
        val conversationId: String,
        val agentId: String,
        val view: ChatBranchView,
        val open: (agentId: String, conversationId: String) -> Unit,
        val isCurrent: () -> Boolean = { true },
    ) {
        val source: BranchSource
            get() = BranchSource(conversationId, agentId, view.messages, historyComplete = !view.hasOlderMessages)
    }

    /** Page actions capture their origin before the shared branch controller starts. */
    inner class Page(private val origin: (ChatBranchView) -> Origin?) : ChatBranchingSource {
        override val support: StateFlow<ChatBranchSupport> = MutableStateFlow(ChatBranchSupport(fork = true))

        override fun forkFrom(message: UiMessage, view: ChatBranchView, name: String?) = from(view) { controller.fork(it.source, message) }

        override fun editAndResend(message: UiMessage, view: ChatBranchView) = from(view) { controller.editAndResend(it.source, message) }

        override fun rerun(message: UiMessage, view: ChatBranchView) = from(view) { controller.rerun(it.source, message) }

        /** letta-mobile-7mys7: `/fork [name]`: all of the conversation, named when given. */
        fun forkConversation(name: String?, view: ChatBranchView) = from(view) { controller.forkConversation(it.source, name) }

        private fun from(view: ChatBranchView, request: (Origin) -> Boolean) {
            val start = origin(view) ?: return
            val previousOpen = open
            val previousAccepts = accepts
            open = start.open
            accepts = start.isCurrent
            if (!request(start)) {
                open = previousOpen
                accepts = previousAccepts
            }
        }
    }
}
