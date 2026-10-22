package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.branch.PendingDraft
import com.letta.mobile.data.chat.conversations.OpenedBranch
import kotlinx.coroutines.CancellationException

/*
 * letta-mobile-7mys7: what the desktop adds to the shared branching
 * (com.letta.mobile.data.chat.conversations.ConversationBranches): the opened branch's prompt in its
 * composer, `/fork` from the composer, and the source's working directory carried to the fork.
 */

/** Puts an opened branch's prompt (text and images) in the composer and, for a rerun, sends it into the fork at once. */
internal fun DesktopChatController.applyBranchDraft(pending: PendingDraft?) {
    val draft = pending?.draft ?: return
    updateComposerText(draft.text)
    draft.images.forEach(::attachImage)
    if (pending.sendNow) send()
}

/** The composer's send: a `/fork [name]` runs as a branch (the App Server does not run it), anything else is sent. */
internal fun DesktopChatController.sendOrBranch() {
    if (conversationManagement.branches.runBranchCommand(state.value.composerText)) updateComposerText("") else send()
}

/** The fork works in the folder the source did (the reference app carries the cwd over too). */
internal suspend fun inheritWorkingDirectory(gateway: DesktopChatGateway, branch: OpenedBranch) {
    val directories = gateway as? DesktopWorkingDirectoryController ?: return
    val agentId = branch.source.agentId ?: return
    try {
        val cwd = directories.currentWorkingDirectory(agentId, branch.source.conversationId) ?: return
        directories.setWorkingDirectory(branch.conversation.agentId.value.ifBlank { agentId }, branch.conversation.id.value, cwd)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The fork still opens; it starts in the runtime's default folder.
    }
}
