package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.branch.BranchOpener
import com.letta.mobile.data.chat.branch.BranchSource
import com.letta.mobile.data.chat.branch.ConversationBranchRunner
import com.letta.mobile.data.chat.branch.ConversationBranching
import com.letta.mobile.data.chat.branch.branchBackendOrNull
import com.letta.mobile.data.chat.runtime.PinnedConversations
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/** The conversation a branch starts from: the selected one. */
internal data class DesktopBranchOrigin(val conversationId: String, val agentId: String?)

/** The page's timeline at the moment the user asked for the branch. */
internal data class DesktopBranchView(val messages: List<UiMessage>, val hasOlderMessages: Boolean)

/** A finished branch: the fork to open and, for an edit, the text for its composer. */
internal data class DesktopOpenedBranch(val conversation: Conversation, val draft: String?)

/** What [DesktopConversationBrancher] needs from the controller. */
internal interface DesktopBranchHost {
    val gateway: DesktopChatGateway?
    val pins: PinnedConversations?

    fun origin(): DesktopBranchOrigin?

    /** Opens the new conversation and, for an edit, puts the draft in its composer. */
    suspend fun openBranch(branch: DesktopOpenedBranch)

    fun showError(message: String)
}

/**
 * letta-mobile-bzvro.15 / .16 (F15, F16): desktop's binding of the shared [ConversationBranching]
 * and [ConversationBranchRunner]. A fork keeps the source's working directory and pinned flag, then
 * opens in place; an edit also puts the prompt's text back in the fork's composer.
 */
internal class DesktopConversationBrancher(
    scope: CoroutineScope,
    private val host: DesktopBranchHost,
) {
    private val runner = ConversationBranchRunner(scope, host::showError)

    /** True when the current backend can fork, so the page offers the actions. */
    val supported: Boolean get() = host.gateway?.branchBackendOrNull() != null

    fun forkFrom(message: UiMessage, view: DesktopBranchView) {
        val (branching, source, gateway) = prepare(view) ?: return
        runner.forkFrom(branching, source, message, opener(gateway))
    }

    fun editAndResend(message: UiMessage, view: DesktopBranchView) {
        val (branching, source, gateway) = prepare(view) ?: return
        runner.editAndResend(branching, source, message, opener(gateway))
    }

    private fun prepare(view: DesktopBranchView): Triple<ConversationBranching, BranchSource, DesktopChatGateway>? {
        val gateway = host.gateway ?: return null
        val origin = host.origin() ?: return null
        val backend = gateway.branchBackendOrNull()
        if (backend == null) {
            host.showError(UNSUPPORTED)
            return null
        }
        val source = BranchSource(origin.conversationId, origin.agentId, view.messages, !view.hasOlderMessages)
        return Triple(ConversationBranching(backend), source, gateway)
    }

    private fun opener(gateway: DesktopChatGateway) = BranchOpener { source, fork, draft ->
        inheritWorkingDirectory(gateway, source, fork)
        host.pins?.inherit(source.conversationId, fork.id.value)
        host.openBranch(DesktopOpenedBranch(fork, draft))
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

    private companion object {
        const val UNSUPPORTED = "This backend can't fork conversations."
    }
}
