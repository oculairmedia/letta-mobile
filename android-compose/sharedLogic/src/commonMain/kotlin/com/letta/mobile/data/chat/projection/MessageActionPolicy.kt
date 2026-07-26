package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiMessage

data class MessageActionAvailability(
    val canCopy: Boolean,
    val canSelectText: Boolean,
    val canSendAgain: Boolean,
) {
    val hasActions: Boolean
        get() = canCopy || canSelectText || canSendAgain
}

fun messageActionAvailability(
    message: UiMessage,
    copyText: String,
    sendAgainAvailable: Boolean,
): MessageActionAvailability {
    val eligibleRole = message.role == "user" || message.role == "assistant"
    val hasText = copyText.isNotBlank()
    if (!eligibleRole || message.isReasoning) {
        return MessageActionAvailability(
            canCopy = false,
            canSelectText = false,
            canSendAgain = false,
        )
    }

    return MessageActionAvailability(
        canCopy = hasText,
        canSelectText = hasText,
        // The current coordinator drops attachments, so a resend would be lossy.
        canSendAgain = sendAgainAvailable &&
            message.role == "user" &&
            message.content.isNotBlank() &&
            message.attachments.isEmpty(),
    )
}
