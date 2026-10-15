package com.letta.mobile.ui.screens.conversations

import com.letta.mobile.data.repository.NoActiveConversationRunException
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.util.mapErrorToUserMessage

/**
 * letta-mobile-by4vm.12: the conversation inspector's actions that only some backends can route.
 * Each carries the line the inspector shows when the backend has no route for it, and the line it
 * shows when the call fails without a better reason.
 */
enum class ConversationAdminAction(val unavailableMessage: String, val failureMessage: String) {
    CancelRuns(
        unavailableMessage = "Cancelling runs needs a desktop or server host over Iroh; this connection has no route for it.",
        failureMessage = "Couldn't cancel the run.",
    ),
    Recompile(
        unavailableMessage = "Recompile isn't available on this connection: the host has no recompile route yet.",
        failureMessage = "Couldn't recompile the conversation.",
    ),
}

/** Which [ConversationAdminAction]s the active backend routes, read from the conversation repository. */
data class ConversationAdminRoutes(val cancelRuns: Boolean, val recompile: Boolean) {
    fun supports(action: ConversationAdminAction): Boolean = when (action) {
        ConversationAdminAction.CancelRuns -> cancelRuns
        ConversationAdminAction.Recompile -> recompile
    }

    /** Null when [action] is routed; otherwise the line saying why it is off. */
    fun unavailableReason(action: ConversationAdminAction): String? = action.unavailableMessage.takeUnless { supports(action) }

    companion object {
        fun of(repository: IConversationRepository) = ConversationAdminRoutes(
            cancelRuns = repository.canCancelConversationRuns(),
            recompile = repository.canRecompileConversations(),
        )
    }
}

/** The line the inspector shows when [action] failed with [error]. */
internal fun ConversationAdminAction.errorMessage(error: Exception): String = when (error) {
    is NoActiveConversationRunException -> error.message ?: failureMessage
    else -> mapErrorToUserMessage(error, failureMessage)
}
