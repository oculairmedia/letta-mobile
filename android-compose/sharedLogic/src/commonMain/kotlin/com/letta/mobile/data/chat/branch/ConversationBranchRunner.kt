package com.letta.mobile.data.chat.branch

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a page does with a finished branch: open [conversation], with [draft] in its composer for an edit. */
fun interface BranchOpener {
    suspend fun open(source: BranchSource, conversation: Conversation, draft: String?)
}

/**
 * letta-mobile-bzvro.15 / .16: runs one fork or edit at a time on [scope] for a chat page, and
 * reports a failure through [onError] as a message fit to show. Both clients drive their
 * "Fork from here" and "Edit and resend" through it; only [BranchOpener] differs (desktop selects
 * the fork in place, Android navigates to it).
 */
class ConversationBranchRunner(
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {
    private var running = false

    fun forkFrom(branching: ConversationBranching, source: BranchSource, message: UiMessage, opener: BranchOpener) =
        launch(source, opener) { branching.forkFrom(source, message) to null }

    fun editAndResend(branching: ConversationBranching, source: BranchSource, message: UiMessage, opener: BranchOpener) =
        launch(source, opener) { branching.editAndResend(source, message).let { it.conversation to it.draft } }

    private fun launch(source: BranchSource, opener: BranchOpener, branch: suspend () -> Pair<Conversation, String?>) {
        if (running) return
        running = true
        scope.launch {
            try {
                val (fork, draft) = branch()
                opener.open(source, fork, draft)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onError(failure.message ?: FAILED)
            } finally {
                running = false
            }
        }
    }

    companion object {
        const val FAILED = "Couldn't branch the conversation."
    }
}
