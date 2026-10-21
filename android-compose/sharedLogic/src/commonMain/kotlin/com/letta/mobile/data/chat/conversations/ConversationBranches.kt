package com.letta.mobile.data.chat.conversations

import com.letta.mobile.data.chat.branch.BranchOpener
import com.letta.mobile.data.chat.branch.BranchOutcome
import com.letta.mobile.data.chat.branch.BranchSlashCommand
import com.letta.mobile.data.chat.branch.BranchSource
import com.letta.mobile.data.chat.branch.BranchState
import com.letta.mobile.data.chat.branch.ConversationBranchController
import com.letta.mobile.data.chat.branch.ConversationBranching
import com.letta.mobile.data.chat.branch.PendingDraft
import com.letta.mobile.data.chat.branch.branchBackendOrNull
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.session.ChatBranchSupport
import com.letta.mobile.ui.chat.session.ChatBranchView
import com.letta.mobile.ui.chat.session.ChatBranchingSource
import com.letta.mobile.ui.chat.session.DerivedStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * Fork, edit-and-resend and rerun for every host: the shared [ConversationBranchController] over the
 * host gateway's fork capability. A branch records its parent, keeps the source's pin, and opens
 * through the host ([ConversationListHost.openBranch]) with its prompt waiting for the composer.
 */
class ConversationBranches internal constructor(
    scope: CoroutineScope,
    private val host: ConversationListHost,
    private val prefs: ConversationManagementPrefs,
) : ChatBranchingSource {
    private val controller = ConversationBranchController(
        scope = scope,
        branching = { host.gateway?.branchBackendOrNull()?.let(::ConversationBranching) },
        opener = BranchOpener(::open),
        lineage = prefs.lineage,
        onError = host::showError,
    )

    /** Whether the active backend can fork, and name a fork; read again when the gateway changes. */
    override val support: StateFlow<ChatBranchSupport> = DerivedStateFlow(host.gatewayChanges.map { current() }, ::current)

    private fun current() = ChatBranchSupport(fork = controller.supported, nameForks = controller.canName)

    val supported: Boolean get() = controller.supported

    /** The fork, edit or rerun in flight, or the last failure. */
    val state: StateFlow<BranchState> get() = controller.state

    override fun forkFrom(message: UiMessage, view: ChatBranchView, name: String?) {
        source(view)?.let { controller.fork(it, message, name) }
    }

    override fun editAndResend(message: UiMessage, view: ChatBranchView) {
        source(view)?.let { controller.editAndResend(it, message) }
    }

    /** Forks before the prompt and sends it again there; the original keeps its answer. */
    override fun rerun(message: UiMessage, view: ChatBranchView) {
        source(view)?.let { controller.rerun(it, message) }
    }

    /** `/fork [name]` sent from the composer (letta-code's terminal command): forks all of the open conversation. */
    override fun runBranchCommand(text: String): Boolean {
        val fork = BranchSlashCommand.parse(text) as? BranchSlashCommand.Fork ?: return false
        val open = host.origin() ?: return false
        if (!supported) return false
        forkConversation(open, fork.name)
        return true
    }

    /** Forks all of [origin], which need not be the open conversation (a row's "Fork chat…"), named [name] when given. */
    fun forkConversation(origin: ConversationOrigin, name: String? = null) {
        if (!host.isClosed) controller.forkConversation(BranchSource(origin.conversationId.value, origin.agentId?.value), name)
    }

    fun dismissFailure() = controller.dismissFailure()

    private fun source(view: ChatBranchView): BranchSource? {
        if (host.isClosed) return null
        val origin = host.origin() ?: return null
        return BranchSource(origin.conversationId.value, origin.agentId?.value, view.messages, historyComplete = !view.hasOlderMessages)
    }

    private suspend fun open(outcome: BranchOutcome) {
        prefs.pins?.inherit(outcome.source.conversationId, outcome.conversation.id.value)
        host.openBranch(OpenedBranch(outcome.conversation, outcome.source, outcome.draft?.let { PendingDraft(it, outcome.sendNow) }))
    }
}
