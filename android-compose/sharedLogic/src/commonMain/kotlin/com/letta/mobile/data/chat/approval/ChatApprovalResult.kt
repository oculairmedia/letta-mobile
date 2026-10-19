package com.letta.mobile.data.chat.approval

/** What became of one approval answer a chat client delivered. */
sealed interface ChatApprovalResult {
    /** The link is down; the answer waits in the outbox and sends on reconnect. */
    data object Queued : ChatApprovalResult
    data object MissingActiveAgent : ChatApprovalResult
    data object MissingActiveConversation : ChatApprovalResult
    data object Submitted : ChatApprovalResult
    data class Failed(val message: String) : ChatApprovalResult
}

/** The answer went out, or waits in the offline outbox: either way it stays in flight. */
val ChatApprovalResult.accepted: Boolean
    get() = this == ChatApprovalResult.Submitted || this == ChatApprovalResult.Queued

/** What the person is told when [this] was not accepted; null for an accepted answer. */
val ChatApprovalResult.errorMessage: String?
    get() = when (this) {
        ChatApprovalResult.Submitted, ChatApprovalResult.Queued -> null
        ChatApprovalResult.MissingActiveAgent -> "No active agent available for approval"
        ChatApprovalResult.MissingActiveConversation -> "No active conversation available for approval"
        is ChatApprovalResult.Failed -> message
    }
