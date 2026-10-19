package com.letta.mobile.data.swarm

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId

/**
 * Everything a swarm send reports, in order. A send's flow emits receipts as
 * each stage is reached ([DeliveryReceipt]), [Activity] for tool traffic it observed, [Retrying]
 * while the link is re-dialled, and ends with exactly one final event: the receipt of the stage the
 * caller waited for, or a [DeliveryFailure]. Expected failures are values, never exceptions.
 */
sealed interface DeliveryEvent

/** The stages a send passes, in order. A caller may stop waiting after any of them. */
enum class DeliveryStage { Connected, Accepted, Persisted, Processed }

sealed interface DeliveryReceipt : DeliveryEvent {
    val stage: DeliveryStage

    /** Dialled and authenticated as this paired device, and the agent and conversation are in its scope. */
    data class Connected(
        val agent: SwarmAgent,
        val conversation: ConversationId,
        val clientMessageId: ClientMessageId,
    ) : DeliveryReceipt {
        override val stage: DeliveryStage get() = DeliveryStage.Connected
    }

    /** The host accepted the input (`input_accepted`): it started a turn, or queued behind a busy agent. */
    data class Accepted(val clientMessageId: ClientMessageId, val disposition: InputDisposition) : DeliveryReceipt {
        override val stage: DeliveryStage get() = DeliveryStage.Accepted
    }

    /** The message is in the conversation history, read back once, under the server's own id. */
    data class Persisted(val messageId: MessageId) : DeliveryReceipt {
        override val stage: DeliveryStage get() = DeliveryStage.Persisted
    }

    /** The turn reached its terminal, with the agent's reply as stored in the history. */
    data class Processed(val terminal: TurnTerminal, val reply: SwarmReply) : DeliveryReceipt {
        override val stage: DeliveryStage get() = DeliveryStage.Processed
    }
}

enum class InputDisposition {
    /** The input started a turn now. */
    Started,

    /** The agent was busy; the input waits in the conversation queue (not a failure). */
    Queued,
}

/** How the turn ended. Only [Unobserved] is not an authoritative server terminal. */
sealed interface TurnTerminal {
    data object Completed : TurnTerminal

    data class Failed(val reason: String) : TurnTerminal

    data class Cancelled(val reason: String) : TurnTerminal

    /** The turn is parked on approvals; this client never answers them, the person does (in the app). */
    data class NeedsApproval(val approvals: List<PendingSwarmApproval>) : TurnTerminal

    /**
     * The link dropped mid-turn and the reply was read back from the history after reconnecting,
     * without seeing the terminal itself: the reply may still be growing.
     */
    data object Unobserved : TurnTerminal
}

data class PendingSwarmApproval(val toolName: String, val toolCallId: String?, val approvalId: String)

/** The agent's reply: its assistant messages after this send's message, bounded to [SwarmLimits.MAX_REPLY_CHARS]. */
data class SwarmReply(val text: String, val messageIds: List<MessageId>, val truncated: Boolean) {
    companion object {
        val Empty: SwarmReply = SwarmReply("", emptyList(), truncated = false)
    }
}

/** Tool traffic seen during the turn (reported in JSON output; never acted on). */
sealed interface Activity : DeliveryEvent {
    data class ToolCall(val toolName: String, val toolCallId: String) : Activity

    data class ToolReturn(val toolCallId: String, val status: String) : Activity

    data class ApprovalRequested(val approval: PendingSwarmApproval) : Activity
}

/** The link failed before the message left; it is re-dialled after [delayMs]. */
data class Retrying(val attempt: Int, val reason: String, val delayMs: Long) : DeliveryEvent

sealed interface DeliveryFailure : DeliveryEvent {
    /** No pairing is stored on this machine. */
    data object NotPaired : DeliveryFailure

    /** The host refused this device (never paired, revoked, or the pairing was replaced). */
    data class Denied(val reason: String) : DeliveryFailure

    /** The host could not be reached within the retry budget. */
    data class Unreachable(val reason: String) : DeliveryFailure

    /** No agent by that id or name is in this device's scope (out-of-scope agents are invisible). */
    data class AgentNotFound(val ref: AgentRef) : DeliveryFailure

    data class AgentAmbiguous(val ref: AgentRef, val matches: List<SwarmAgent>) : DeliveryFailure

    data class ConversationNotFound(val conversation: ConversationId, val reason: String) : DeliveryFailure

    /** The conversation exists but belongs to another agent: refused here, whatever the host allows. */
    data class ConversationOutOfScope(val conversation: ConversationId, val owner: AgentId) : DeliveryFailure

    /** The host answered a request with an error (permission denial, bad request, ...). */
    data class HostError(val operation: String, val reason: String) : DeliveryFailure

    /** The host rejected the input (`input_accepted{accepted:false}`). */
    data class Rejected(val reason: String) : DeliveryFailure

    /** The deadline passed; [reached] is the last stage confirmed. The server turn is left running. */
    data class TimedOut(val reached: DeliveryStage?) : DeliveryFailure

    /**
     * The link dropped after the input may have left, and the history does not show the message:
     * it is neither re-sent (that could duplicate it) nor known lost. Check with `message status`.
     */
    data class Unconfirmed(val clientMessageId: ClientMessageId) : DeliveryFailure

    /** The outbox entry outlived its time to live without being sent. */
    data class Expired(val clientMessageId: ClientMessageId) : DeliveryFailure

    /** A resumed id was first used for a different message (agent, conversation or text differ). */
    data class IdReused(val clientMessageId: ClientMessageId) : DeliveryFailure
}

object SwarmLimits {
    /** Largest message the CLI sends (characters, sender line included). */
    const val MAX_MESSAGE_CHARS: Int = 32_000

    /** Reply text is cut at this many characters ([SwarmReply.truncated]). */
    const val MAX_REPLY_CHARS: Int = 64_000

    /** Per-message content cap in `message read`. */
    const val MAX_READ_CONTENT_CHARS: Int = 16_000

    /** Most messages one `message read` returns. */
    const val MAX_READ_MESSAGES: Int = 200

    /** Most agents `agents list` pages through. */
    const val MAX_AGENTS: Int = 500
}
