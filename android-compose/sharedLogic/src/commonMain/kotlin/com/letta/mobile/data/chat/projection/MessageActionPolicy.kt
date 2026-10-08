package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.chat.branch.ConversationBranchPlanner
import com.letta.mobile.data.model.UiMessage

data class MessageActionAvailability(
    val canCopy: Boolean,
    val canSelectText: Boolean,
    val canSendAgain: Boolean,
    /** letta-mobile-bzvro.15: "Fork from here". */
    val canFork: Boolean = false,
    /** letta-mobile-bzvro.16: "Edit and resend". */
    val canEdit: Boolean = false,
) {
    val hasActions: Boolean
        get() = canCopy || canSelectText || canSendAgain || canFork || canEdit
}

/** Which branch actions the conversation's owner supports (its ChatSurfaceCapabilities). */
data class BranchActionSupport(val fork: Boolean = false, val edit: Boolean = false) {
    companion object {
        val None = BranchActionSupport()
    }
}

fun messageActionAvailability(
    message: UiMessage,
    copyText: String,
    sendAgainAvailable: Boolean,
    branching: BranchActionSupport = BranchActionSupport.None,
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
        canSendAgain = sendAgainAvailable && isResendable(message),
        canFork = branching.fork && ConversationBranchPlanner.canFork(message),
        canEdit = branching.edit && canEditText(message),
    )
}

/** The current coordinator drops attachments, so a resend of a prompt with images would be lossy. */
private fun isResendable(message: UiMessage): Boolean =
    message.role == "user" && message.content.isNotBlank() && message.attachments.isEmpty()

/** An edit refills the composer with text only, so a prompt with images would lose them. */
private fun canEditText(message: UiMessage): Boolean =
    ConversationBranchPlanner.canEdit(message) && message.attachments.isEmpty()
