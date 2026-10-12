package com.letta.mobile.feature.chat.screen.messageactions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.chat.projection.BranchActionSupport
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.screen.AdminChatViewModel

/**
 * letta-mobile-7mys7: the legacy Android message list's fork, edit-and-resend and rerun. The screen
 * provides them where it can open the fork and the backend can make it; null (the default) keeps
 * the sheet to Copy and Send again. Provided as a local so the long list wiring between the
 * screen and each row stays unchanged.
 */
@Immutable
internal data class MessageBranchActions(
    val onFork: (UiMessage) -> Unit,
    val onEditAndResend: (UiMessage) -> Unit,
    val onRerun: (UiMessage) -> Unit,
) {
    val support: BranchActionSupport get() = BranchActionSupport(fork = true, edit = true, rerun = true)
}

internal val LocalMessageBranchActions = staticCompositionLocalOf<MessageBranchActions?> { null }

/** The legacy page's branch actions: null without a way to open the fork or a backend that can make one. */
@Composable
internal fun rememberMessageBranchActions(
    viewModel: AdminChatViewModel,
    onOpenConversation: ((agentId: String, conversationId: String) -> Unit)?,
): MessageBranchActions? {
    val canBranch = viewModel.canBranchConversations
    return remember(viewModel, onOpenConversation, canBranch) {
        val open = onOpenConversation?.takeIf { canBranch } ?: return@remember null
        MessageBranchActions(
            onFork = { viewModel.forkFromMessage(it, open) },
            onEditAndResend = { viewModel.editAndResend(it, open) },
            onRerun = { viewModel.rerunInFork(it, open) },
        )
    }
}
