package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope

/**
 * Retry classification for App Server commands interrupted by an ambiguous
 * disconnect, derived from the lgns8.15 restart/replay evidence
 * (appserver-cli/src/test/resources/appserver/restart-replay-evidence.json):
 *
 * - `runtime_start` reattach and `sync` are safe reads: replaying them after
 *   reconnect cannot duplicate committed state.
 * - `input` with `create_message` is an ambiguous write: the server does NOT
 *   dedupe by `client_message_id`, so a blind resend duplicates the user
 *   message and re-runs the turn. Reconcile against the committed transcript
 *   first ([AmbiguousTurnReconciler]).
 * - Approval decisions, external tool results, and aborts are non-idempotent
 *   control writes: never auto-replay; surface to the caller instead.
 * - `run_id`, `event_seq`, and `idempotency_key` reset across restarts and are
 *   forbidden as cross-generation retry anchors; `client_message_id` (committed
 *   as the transcript `otid`) is the only durable correlation identity.
 */
enum class AppServerRetryClass {
    /** Replaying after reconnect is safe; retry freely under backoff. */
    SAFE_READ,

    /** May have committed before the disconnect; reconcile before any resend. */
    AMBIGUOUS_WRITE,

    /** Non-idempotent control decision; never auto-replay, escalate instead. */
    NON_IDEMPOTENT_CONTROL,
}

object AppServerRetryPolicy {
    fun classify(command: AppServerCommand): AppServerRetryClass = when (command) {
        is AppServerCommand.Auth -> AppServerRetryClass.SAFE_READ
        is AppServerCommand.RuntimeStart -> AppServerRetryClass.SAFE_READ
        is AppServerCommand.Sync -> AppServerRetryClass.SAFE_READ
        is AppServerCommand.Input -> when (command.payload) {
            is AppServerInputPayload.ApprovalResponse -> AppServerRetryClass.NON_IDEMPOTENT_CONTROL
            else -> AppServerRetryClass.AMBIGUOUS_WRITE
        }
        is AppServerCommand.AbortMessage -> AppServerRetryClass.NON_IDEMPOTENT_CONTROL
        is AppServerCommand.ExternalToolCallResponse -> AppServerRetryClass.NON_IDEMPOTENT_CONTROL
        is AppServerCommand.AdminRpc -> classifyAdminRpc(command.method)
    }

    /**
     * Admin RPC retryability by method name. Reads retry safely; anything not
     * positively known to be a read is treated as an ambiguous write.
     */
    fun classifyAdminRpc(method: String): AppServerRetryClass {
        val isRead = READ_METHOD_SUFFIXES.any { method.endsWith(it) } || method in READ_METHODS
        return if (isRead) AppServerRetryClass.SAFE_READ else AppServerRetryClass.AMBIGUOUS_WRITE
    }

    private val READ_METHOD_SUFFIXES = listOf(".list", ".get", ".check", ".list_agent", ".list.embedding")
    private val READ_METHODS = setOf(
        "project.beadsRemoteStatus",
        "subagent.todos",
        "message.list",
    )
}

/** How an in-flight turn input should be handled after an ambiguous disconnect. */
sealed interface AmbiguousTurnResolution {
    /** The input never committed; resending the same `client_message_id` is safe. */
    data object ResendSafe : AmbiguousTurnResolution

    /**
     * The user message committed and a later assistant/terminal message exists:
     * the turn completed. Do not resend; rehydrate the transcript instead.
     */
    data object AlreadyCompleted : AmbiguousTurnResolution

    /**
     * The user message committed but no assistant turn followed (mid-turn
     * kill). Do NOT re-append the input; the turn must be re-driven without a
     * duplicate user message, or escalated to the user.
     */
    data object CommittedWithoutAssistantTurn : AmbiguousTurnResolution
}

/** Reads the committed transcript to resolve ambiguous inputs by `otid`. */
interface CommittedTranscriptInspector {
    /**
     * Whether a committed user message with `otid == clientMessageId` exists,
     * and if so whether an assistant/terminal message follows it.
     * Returns null when the user message is absent.
     */
    suspend fun committedTurnState(
        runtime: AppServerRuntimeScope,
        clientMessageId: String,
    ): CommittedTurnState?
}

data class CommittedTurnState(val assistantTurnCompleted: Boolean)

/**
 * Resolves whether an input interrupted by socket loss may be resent, per the
 * lgns8.15 reconciliation rules: match the committed transcript by
 * `otid == client_message_id`; only resend if absent; if committed without a
 * completed assistant turn, re-drive without re-appending the user message.
 */
class AmbiguousTurnReconciler(
    private val inspector: CommittedTranscriptInspector,
) {
    suspend fun resolve(
        runtime: AppServerRuntimeScope,
        clientMessageId: String,
    ): AmbiguousTurnResolution {
        val committed = inspector.committedTurnState(runtime, clientMessageId)
            ?: return AmbiguousTurnResolution.ResendSafe
        return if (committed.assistantTurnCompleted) {
            AmbiguousTurnResolution.AlreadyCompleted
        } else {
            AmbiguousTurnResolution.CommittedWithoutAssistantTurn
        }
    }
}
