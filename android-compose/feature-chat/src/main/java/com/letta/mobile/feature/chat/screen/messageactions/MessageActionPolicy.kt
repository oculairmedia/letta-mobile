package com.letta.mobile.feature.chat.screen.messageactions

import com.letta.mobile.data.model.UiMessage

internal data class MessageActionAvailability(
    val canCopy: Boolean,
    val canSelectText: Boolean,
    val canSendAgain: Boolean,
) {
    val hasActions: Boolean
        get() = canCopy || canSelectText || canSendAgain
}

internal fun messageActionAvailability(
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
        // The current coordinator drops attachments. Hiding this action for
        // multimodal messages prevents a misleading text-only resend until
        // the send boundary can preserve the original attachment payload.
        canSendAgain = sendAgainAvailable &&
            message.role == "user" &&
            message.content.isNotBlank() &&
            message.attachments.isEmpty(),
    )
}
