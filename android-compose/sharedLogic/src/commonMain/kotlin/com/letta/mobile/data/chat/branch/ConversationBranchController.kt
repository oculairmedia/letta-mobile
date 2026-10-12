package com.letta.mobile.data.chat.branch

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a branch does with the conversation it makes. */
enum class BranchKind {
    /** "Fork from here" (or the drawer's fork): open the copy. */
    Fork,

    /** "Edit and resend": open the copy with the prompt in its composer. */
    Edit,

    /** "Rerun": open the copy and send the prompt again. */
    Rerun,
}

/** A finished branch for the page to open. */
data class BranchOutcome(
    val kind: BranchKind,
    val source: BranchSource,
    val conversation: Conversation,
    /** The prompt for the composer (an edit) or to send (a rerun); null for a fork. */
    val draft: BranchDraft? = null,
) {
    /** A rerun sends the draft as soon as the fork is open; an edit waits for the user. */
    val sendNow: Boolean get() = kind == BranchKind.Rerun
}

/** What a page does with a finished branch: open it, put the draft in its composer, and send it for a rerun. */
fun interface BranchOpener {
    suspend fun open(outcome: BranchOutcome)
}

/** The branch the page is waiting for, if any; the page shows progress and failures from it. */
sealed interface BranchState {
    data object Idle : BranchState

    data class Working(val kind: BranchKind, val messageId: String?) : BranchState

    data class Failed(val kind: BranchKind, val message: String) : BranchState
}

/**
 * letta-mobile-7mys7: the one fork / edit-and-resend / rerun controller every surface drives. It
 * runs one branch at a time on [scope], records the fork's parent in [lineage], hands the result
 * to [opener], and reports progress and failures through [state] (and [onError], for pages that
 * show errors in their own banner).
 *
 * [branching] is read per request, because the backend can change while the page is open; null
 * means the backend cannot branch. [isOnline] fails a request at once when the connection is down,
 * rather than waiting on a request that cannot go out.
 */
class ConversationBranchController(
    private val scope: CoroutineScope,
    private val branching: () -> ConversationBranching?,
    private val opener: BranchOpener,
    private val lineage: ConversationLineage? = null,
    private val isOnline: () -> Boolean = { true },
    private val onError: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<BranchState>(BranchState.Idle)
    val state: StateFlow<BranchState> = _state.asStateFlow()

    /** True when the backend can branch, so the page offers the actions. */
    val supported: Boolean get() = branching() != null

    /** True when a fork can be given a name. */
    val canName: Boolean get() = branching()?.canName == true

    /** Forks [source] from [message], naming the fork [name] when given. */
    fun fork(source: BranchSource, message: UiMessage, name: String? = null): Boolean =
        start(BranchKind.Fork, source, message.id) { BranchOutcome(BranchKind.Fork, source, it.forkFrom(source, message, name)) }

    /** Forks all of [source] (the drawer's "Fork"), naming the fork [name] when given. */
    fun forkConversation(source: BranchSource, name: String? = null): Boolean =
        start(BranchKind.Fork, source, null) { BranchOutcome(BranchKind.Fork, source, it.forkConversation(source, name)) }

    fun editAndResend(source: BranchSource, message: UiMessage): Boolean =
        start(BranchKind.Edit, source, message.id) { b ->
            b.editAndResend(source, message).let { BranchOutcome(BranchKind.Edit, source, it.conversation, it.prompt) }
        }

    fun rerun(source: BranchSource, message: UiMessage): Boolean =
        start(BranchKind.Rerun, source, message.id) { b ->
            b.rerun(source, message).let { BranchOutcome(BranchKind.Rerun, source, it.conversation, it.prompt) }
        }

    /** The page showed the failure. */
    fun dismissFailure() {
        if (_state.value is BranchState.Failed) _state.value = BranchState.Idle
    }

    /** Starts the branch unless one is running; false when it did not start. */
    private fun start(
        kind: BranchKind,
        source: BranchSource,
        messageId: String?,
        branch: suspend (ConversationBranching) -> BranchOutcome,
    ): Boolean {
        if (_state.value is BranchState.Working) return false
        val current = branching() ?: return fail(kind, UNSUPPORTED)
        if (!isOnline()) return fail(kind, OFFLINE)
        _state.value = BranchState.Working(kind, messageId)
        scope.launch {
            try {
                val outcome = branch(current)
                lineage?.record(outcome.conversation.id.value, ConversationParent(source.conversationId, kind))
                _state.value = BranchState.Idle
                opener.open(outcome)
            } catch (cancelled: CancellationException) {
                _state.value = BranchState.Idle
                throw cancelled
            } catch (failure: Exception) {
                fail(kind, failure.message?.takeIf { it.isNotBlank() } ?: FAILED)
            }
        }
        return true
    }

    private fun fail(kind: BranchKind, message: String): Boolean {
        _state.value = BranchState.Failed(kind, message)
        onError(message)
        return false
    }

    companion object {
        const val FAILED = "Couldn't branch the conversation."
        const val UNSUPPORTED = "This backend can't fork conversations."
        const val OFFLINE = "You're offline, so the conversation can't be forked right now."
    }
}
