package com.letta.mobile.ui.chat.render

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableSet

/**
 * The small slice of screen state that can change how an individual chat
 * render item looks. Keeping this separate from [ChatUiState] lets settled
 * LazyColumn items remain skippable while the streaming tail updates
 * [ChatUiState.messages].
 */
@Immutable
data class ChatRenderItemState(
    val isStreaming: Boolean,
    val activeApprovalRequestId: String?,
    val collapsedRunIds: ImmutableSet<String>,
    val expandedReasoningMessageIds: ImmutableSet<String>,
)

fun ChatUiState.toChatRenderItemState(): ChatRenderItemState =
    ChatRenderItemState(
        isStreaming = isStreaming,
        activeApprovalRequestId = activeApprovalRequestId,
        collapsedRunIds = collapsedRunIds,
        expandedReasoningMessageIds = expandedReasoningMessageIds,
    )
