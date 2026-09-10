package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiMessage

data class MessageActionAvailability(
    val canCopy: Boolean,
    val canSelectText: Boolean,
    val canSendAgain: Boolean,
    val canDiscard: Boolean = false,
) {
    val hasActions: Boolean
        get() = canCopy || canSelectText || canSendAgain || canDiscard
}

fun messageActionAvailability(
    message: UiMessage,
    copyText: String,
    sendAgainAvailable: Boolean,
    discardAvailable: Boolean = false,
): MessageActionAvailability {
    val eligibleRole = message.role == "user" || message.role == "assistant"
    val hasText = copyText.isNotBlank()
    if (!eligibleRole || message.isReasoning) {
        return MessageActionAvailability(
            canCopy = false,
            canSelectText = false,
            canSendAgain = false,
            // A send that failed is durable and nothing else removes it, so offer the way out
            // even for a message that has no other action.
            canDiscard = discardAvailable && message.isSendFailed,
        )
    }

    return MessageActionAvailability(
        canDiscard = discardAvailable && message.isSendFailed,
        canCopy = hasText,
        canSelectText = hasText,
        // The current coordinator drops attachments, so a resend would be lossy.
        canSendAgain = sendAgainAvailable &&
            message.role == "user" &&
            message.content.isNotBlank() &&
            message.attachments.isEmpty(),
    )
}
