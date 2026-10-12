package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.branch.BranchDraft
import com.letta.mobile.data.chat.branch.BranchOpener
import com.letta.mobile.data.chat.branch.BranchOutcome
import com.letta.mobile.data.chat.branch.BranchSlashCommand
import com.letta.mobile.data.chat.branch.BranchSource
import com.letta.mobile.data.chat.branch.BranchState
import com.letta.mobile.data.chat.branch.ConversationBranchController
import com.letta.mobile.data.chat.branch.ConversationBranching
import com.letta.mobile.data.chat.branch.ConversationLineage
import com.letta.mobile.data.chat.branch.branchBackendOrNull
import com.letta.mobile.data.chat.runtime.PinnedConversations
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** The conversation a branch starts from: the selected one. */
internal data class DesktopBranchOrigin(val conversationId: String, val agentId: String?)

/** The page's timeline at the moment the user asked for the branch. */
internal data class DesktopBranchView(val messages: List<UiMessage>, val hasOlderMessages: Boolean)

/** A finished branch: the fork to open, the prompt for its composer, and whether to send it at once (a rerun). */
internal data class DesktopOpenedBranch(
    val conversation: Conversation,
    val draft: BranchDraft? = null,
    val sendNow: Boolean = false,
)

/**
 * letta-mobile-7mys7: puts an opened branch's prompt (text and images) in the composer and, for a
 * rerun, sends it into the fork at once.
 */
internal fun DesktopChatController.applyBranchDraft(branch: DesktopOpenedBranch) {
    val draft = branch.draft ?: return
    updateComposerText(draft.text)
    draft.images.forEach(::attachImage)
    if (branch.sendNow) send()
}

/**
 * letta-mobile-7mys7: `/fork [name]` in the composer forks the selected conversation (letta-code's
 * terminal command, which the App Server does not run) instead of sending it as a message. True
 * when the draft was that command and the fork started.
 */
internal fun DesktopChatController.runBranchCommand(): Boolean {
    val fork = BranchSlashCommand.parse(state.value.composerText) as? BranchSlashCommand.Fork ?: return false
    val selected = state.value.selectedConversation ?: return false
    if (!conversationManagement.supportsBranching) return false
    conversationManagement.forkConversation(DesktopBranchOrigin(selected.id, selected.agentId?.takeIf(String::isNotBlank)), fork.name)
    updateComposerText("")
    return true
}

/** The composer's send: a `/fork` runs as a branch, anything else is sent. */
internal fun DesktopChatController.sendOrBranch() {
    if (!runBranchCommand()) send()
}

/** What [DesktopConversationBrancher] needs from the controller. */
internal interface DesktopBranchHost {
    val gateway: DesktopChatGateway?
    val pins: PinnedConversations?

    /** Where forks record their parent; null keeps no lineage. */
    val lineage: ConversationLineage? get() = null

    fun origin(): DesktopBranchOrigin?

    /** Opens the new conversation; for an edit or rerun puts the prompt in its composer, and for a rerun sends it. */
    suspend fun openBranch(branch: DesktopOpenedBranch)

    fun showError(message: String)
}

/**
 * letta-mobile-bzvro.15 / .16, letta-mobile-7mys7: desktop's binding of the shared
 * [ConversationBranchController]. A fork keeps the source's working directory and pinned flag,
 * then opens in place; an edit also puts the prompt back in the fork's composer, and a rerun sends
 * it.
 */
internal class DesktopConversationBrancher(
    scope: CoroutineScope,
    private val host: DesktopBranchHost,
) {
    private val controller = ConversationBranchController(
        scope = scope,
        branching = { host.gateway?.branchBackendOrNull()?.let(::ConversationBranching) },
        opener = BranchOpener(::open),
        lineage = host.lineage,
        onError = host::showError,
    )

    /** True when the current backend can fork, so the page offers the actions. */
    val supported: Boolean get() = controller.supported

    /** True when a fork can be named. */
    val canName: Boolean get() = controller.canName

    val state: StateFlow<BranchState> get() = controller.state

    fun forkFrom(message: UiMessage, view: DesktopBranchView, name: String? = null) {
        source(view)?.let { controller.fork(it, message, name) }
    }

    /** Forks all of [origin], which need not be the selected conversation (a sidebar row's "Fork chat…"). */
    fun forkConversation(origin: DesktopBranchOrigin, name: String? = null) {
        controller.forkConversation(BranchSource(origin.conversationId, origin.agentId?.takeIf(String::isNotBlank)), name)
    }

    fun editAndResend(message: UiMessage, view: DesktopBranchView) {
        source(view)?.let { controller.editAndResend(it, message) }
    }

    fun rerun(message: UiMessage, view: DesktopBranchView) {
        source(view)?.let { controller.rerun(it, message) }
    }

    fun dismissFailure() = controller.dismissFailure()

    private fun source(view: DesktopBranchView): BranchSource? {
        val origin = host.origin() ?: return null
        return BranchSource(origin.conversationId, origin.agentId, view.messages, !view.hasOlderMessages)
    }

    private suspend fun open(outcome: BranchOutcome) {
        host.gateway?.let { inheritWorkingDirectory(it, outcome.source, outcome.conversation) }
        host.pins?.inherit(outcome.source.conversationId, outcome.conversation.id.value)
        host.openBranch(DesktopOpenedBranch(outcome.conversation, outcome.draft, outcome.sendNow))
    }

    /** The fork works in the folder the source did (the reference app carries the cwd over too). */
    private suspend fun inheritWorkingDirectory(gateway: DesktopChatGateway, source: BranchSource, fork: Conversation) {
        val directories = gateway as? DesktopWorkingDirectoryController ?: return
        val agentId = source.agentId ?: return
        try {
            val cwd = directories.currentWorkingDirectory(agentId, source.conversationId) ?: return
            directories.setWorkingDirectory(fork.agentId.value.ifBlank { agentId }, fork.id.value, cwd)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The fork still opens; it starts in the runtime's default folder.
        }
    }
}
