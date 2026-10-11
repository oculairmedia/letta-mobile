package com.letta.mobile.data.chat.recompile

import com.letta.mobile.data.transport.appserver.AppServerRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which conversation to recompile, and the agent it belongs to (needed for `default`). */
data class ConversationRecompileRequest(
    val conversationId: String,
    val agentId: String,
)

/**
 * letta-mobile-zxk2n.5 (G92): optional gateway capability behind `/recompile`: re-render the
 * agent's system prompt (its memory blocks) for a conversation. A direct App Server sends
 * `conversation_recompile`; a REST backend calls `POST /v1/conversations/{id}/recompile`. Iroh has
 * no route yet (the host needs a relay), so its gateway does not implement this. Callers probe with
 * `gateway as? ConversationRecompileGateway`, as they do for forks.
 */
interface ConversationRecompileGateway {
    /** Returns once the server has recompiled; throws with the server's reason when it refuses. */
    suspend fun recompileConversation(request: ConversationRecompileRequest)
}

/** Where a `/recompile` stands, for the chat to show. */
sealed interface RecompileStatus {
    /** Nothing asked, or the last answer has been dismissed. */
    data object Idle : RecompileStatus

    /** Sent; the server has not answered yet. */
    data object Queued : RecompileStatus

    data object Succeeded : RecompileStatus

    /** The server took longer than the request window; the recompile may still finish. */
    data object StillRunning : RecompileStatus

    data class Failed(val reason: String) : RecompileStatus

    /** This connection cannot recompile; [reason] says why, for the user. */
    data class Unavailable(val reason: String) : RecompileStatus

    /** The line the chat shows for this status, or null for [Idle]. */
    fun message(): String? = when (this) {
        Idle -> null
        Queued -> "Recompiling the system prompt…"
        Succeeded -> "System prompt recompiled"
        StillRunning -> "Recompile is taking a while; it may still finish"
        is Failed -> "Recompile failed: $reason"
        is Unavailable -> reason
    }

    /** True for answers worth keeping on screen until dismissed (errors), false for transient ones. */
    val isError: Boolean get() = this is Failed || this is Unavailable
}

/**
 * Runs `/recompile` against whatever [gateway] the chat has and publishes its [status]. A second
 * request while one is queued is ignored, so a double Enter sends one command.
 */
class ConversationRecompileController(
    private val scope: CoroutineScope,
    private val gateway: () -> Any?,
    /** Why this connection cannot recompile, when its gateway has no recompile route. */
    private val unavailableReason: () -> String = { UNAVAILABLE_DEFAULT },
) {
    private val _status = MutableStateFlow<RecompileStatus>(RecompileStatus.Idle)
    val status: StateFlow<RecompileStatus> = _status.asStateFlow()
    private var job: Job? = null

    fun recompile(request: ConversationRecompileRequest) {
        if (_status.value == RecompileStatus.Queued) return
        val recompiler = gateway() as? ConversationRecompileGateway
        if (recompiler == null) {
            _status.value = RecompileStatus.Unavailable(unavailableReason())
            return
        }
        _status.value = RecompileStatus.Queued
        job = scope.launch { _status.value = run(recompiler, request) }
    }

    fun dismiss() {
        if (_status.value != RecompileStatus.Queued) _status.value = RecompileStatus.Idle
    }

    fun cancel() {
        job?.cancel()
        _status.value = RecompileStatus.Idle
    }

    private suspend fun run(recompiler: ConversationRecompileGateway, request: ConversationRecompileRequest): RecompileStatus =
        try {
            recompiler.recompileConversation(request)
            RecompileStatus.Succeeded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AppServerRequestTimeoutException) {
            RecompileStatus.StillRunning
        } catch (_: UnsupportedOperationException) {
            // A wrapping gateway whose backend has no route: the host's reason reads better.
            RecompileStatus.Unavailable(unavailableReason())
        } catch (error: Exception) {
            RecompileStatus.Failed(error.message?.takeIf { it.isNotBlank() } ?: "the server gave no reason")
        }

    companion object {
        const val UNAVAILABLE_DEFAULT = "Recompile isn't available on this connection."

        /** Iroh: the host has no `conversation_recompile` relay yet. */
        const val UNAVAILABLE_OVER_IROH =
            "Recompile isn't available over Iroh yet: the host needs a relay for it. Use a direct App Server or REST connection."
    }
}
