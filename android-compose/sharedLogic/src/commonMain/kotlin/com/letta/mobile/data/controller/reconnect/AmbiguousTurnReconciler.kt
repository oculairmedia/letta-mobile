package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope

/**
 * Reconciliation for turn inputs interrupted by an ambiguous disconnect
 * (letta-mobile-lgns8.5). Classification of commands lives in
 * [com.letta.mobile.data.transport.appserver.AppServerCommandRetryClass]; this
 * type answers the follow-up question for an `AmbiguousMutation` carrying a
 * `client_message_id` dedup key: may it actually be resent?
 *
 * Per the lgns8.15 restart/replay evidence
 * (appserver-cli/src/test/resources/appserver/restart-replay-evidence.json),
 * the pinned App Server does NOT dedupe by `client_message_id` — resending a
 * committed input duplicates the user message and re-runs the turn. The only
 * safe procedure is to inspect the committed transcript for a user message
 * whose `otid` equals the `client_message_id` and resend only if absent.
 * `run_id`, `event_seq`, and `idempotency_key` reset across restarts and must
 * never be used as cross-generation anchors.
 */
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
