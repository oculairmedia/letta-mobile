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
    /** letta-mobile-7mys7: "Rerun" (fork before the prompt and send it again there). */
    val canRerun: Boolean = false,
) {
    val hasActions: Boolean
        get() = canCopy || canSelectText || canSendAgain || canFork || canEdit || canRerun
}

/** Which branch actions the conversation's owner supports (its ChatSurfaceCapabilities). */
data class BranchActionSupport(val fork: Boolean = false, val edit: Boolean = false, val rerun: Boolean = false) {
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
    if (!eligibleRole || message.isReasoning) {
        return MessageActionAvailability(
            canCopy = false,
            canSelectText = false,
            canSendAgain = false,
        )
    }
    val hasText = copyText.isNotBlank()
    return branching.applyTo(
        message,
        MessageActionAvailability(
            canCopy = hasText,
            canSelectText = hasText,
            canSendAgain = sendAgainAvailable && isResendable(message),
        ),
    )
}

/** letta-mobile-7mys7: the branch actions [message] allows, given what the owner supports. */
private fun BranchActionSupport.applyTo(message: UiMessage, base: MessageActionAvailability): MessageActionAvailability =
    base.copy(
        canFork = fork && ConversationBranchPlanner.canFork(message),
        canEdit = edit && ConversationBranchPlanner.canEdit(message) && imagesKept(message),
        canRerun = rerun && ConversationBranchPlanner.canRerun(message),
    )

/** The current coordinator drops attachments, so a resend of a prompt with images would be lossy. */
private fun isResendable(message: UiMessage): Boolean =
    message.role == "user" && message.content.isNotBlank() && message.attachments.isEmpty()

/**
 * letta-mobile-7mys7: an edit puts the prompt's images back in the composer with its text, so it
 * needs their bytes; an image the timeline only keeps a pointer to would be lost.
 */
private fun imagesKept(message: UiMessage): Boolean = message.attachments.all { it.base64.isNotEmpty() }
