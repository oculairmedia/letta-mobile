package com.letta.mobile.feature.chat.screen.messagelist

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.runtime.ChatViewportFollowPolicy
import com.letta.mobile.feature.chat.screen.ChatAutoScrollSignature
import com.letta.mobile.feature.chat.screen.ChatFadeEdgeLength
import com.letta.mobile.feature.chat.screen.ChatMessageRoles
import com.letta.mobile.feature.chat.screen.StreamingAutoScrollSnapThrottleMs
import com.letta.mobile.feature.chat.screen.newestMessageAutoScrollSignature
import com.letta.mobile.feature.chat.screen.shouldForceScrollOnUserSend
import com.letta.mobile.feature.chat.screen.toChatViewportSnapshot
import com.letta.mobile.ui.chat.render.ConversationState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class ChatMessageListConversationResetParams(
    val conversationId: String?,
    val renderItems: List<ChatRenderItem>,
    val listState: LazyListState,
)

internal data class ChatMessageListAutoScrollGate(
    val signature: ChatAutoScrollSignature,
    val previousSignature: ChatAutoScrollSignature?,
    val followLatest: Boolean,
    val renderItemCount: Int,
)

internal data class ChatMessageListPerformAutoScrollParams(
    val listState: LazyListState,
    val isStreaming: Boolean,
    val lastStreamingSnapMs: Long,
)

@Composable
internal fun ChatMessageListEffects(params: ChatMessageListEffectsParams) {
    ChatMessageListFocusClearEffect(params.listState)
    ChatMessageListConversationResetEffect(
        ChatMessageListConversationResetParams(
            conversationId = (params.state.conversationState as? ConversationState.Ready)?.conversationId,
            renderItems = params.renderItems,
            listState = params.listState,
        ),
    )
    ChatMessageListAutoScrollEffect(params)
    ChatMessageListLoadOlderEffect(params)
    ChatMessageListScrollToMessageEffect(params)
}

@Composable
private fun ChatMessageListFocusClearEffect(listState: LazyListState) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(listState.interactionSource) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                focusManager.clearFocus()
            }
        }
    }
}

@Composable
private fun ChatMessageListConversationResetEffect(params: ChatMessageListConversationResetParams) {
    LaunchedEffect(params.conversationId) {
        if (params.renderItems.isNotEmpty()) {
            params.listState.scrollToItem(0)
        }
    }
}

@Composable
private fun ChatMessageListAutoScrollEffect(params: ChatMessageListEffectsParams) {
    val density = LocalDensity.current
    val autoScrollSignature = newestMessageAutoScrollSignature(params.state.messages)
    val isStreamingForAutoScroll by rememberUpdatedState(params.state.isStreaming)
    val conversationId = (params.state.conversationState as? ConversationState.Ready)?.conversationId

    var lastStreamingSnapMs by remember { mutableStateOf(0L) }
    var followLatest by remember(conversationId) { mutableStateOf(true) }
    val lastAutoScrollSignature = remember { mutableStateOf<ChatAutoScrollSignature?>(null) }

    LaunchedEffect(conversationId) {
        followLatest = true
        if (params.renderItems.isNotEmpty()) {
            params.listState.scrollToItem(0)
        }
    }

    LaunchedEffect(params.listState, params.isUserScrolling, params.renderItems.size) {
        snapshotFlow { params.listState.toChatViewportSnapshot(params.isUserScrolling, params.renderItems.size) }
            .distinctUntilChanged()
            .collect { snapshot ->
                followLatest = ChatViewportFollowPolicy.nextFollowModeAfterScroll(
                    currentFollowMode = followLatest,
                    snapshot = snapshot,
                )
            }
    }

    LaunchedEffect(autoScrollSignature, isStreamingForAutoScroll, params.renderItems.size) {
        val signature = autoScrollSignature ?: return@LaunchedEffect
        val previousSignature = lastAutoScrollSignature.value

        if (shouldForceScrollOnUserSend(signature, previousSignature?.messageId)) {
            followLatest = true
            val sendScrollOffset = with(density) { -ChatFadeEdgeLength.roundToPx() }
            params.listState.animateScrollToItem(0, sendScrollOffset)
        } else if (
            shouldAutoScrollToLatest(
                ChatMessageListAutoScrollGate(
                    signature = signature,
                    previousSignature = previousSignature,
                    followLatest = followLatest,
                    renderItemCount = params.renderItems.size,
                ),
            )
        ) {
            lastStreamingSnapMs = performAutoScrollToLatest(
                ChatMessageListPerformAutoScrollParams(
                    listState = params.listState,
                    isStreaming = isStreamingForAutoScroll,
                    lastStreamingSnapMs = lastStreamingSnapMs,
                ),
            )
        }

        lastAutoScrollSignature.value = signature
    }
}

private fun shouldAutoScrollToLatest(gate: ChatMessageListAutoScrollGate): Boolean {
    if (!ChatViewportFollowPolicy.shouldAutoFollow(gate.followLatest, gate.renderItemCount)) return false
    return gate.signature.role != ChatMessageRoles.User ||
        gate.signature.messageId != gate.previousSignature?.messageId
}

private suspend fun performAutoScrollToLatest(params: ChatMessageListPerformAutoScrollParams): Long {
    val nowMs = System.currentTimeMillis()
    if (params.isStreaming) {
        if (nowMs - params.lastStreamingSnapMs < StreamingAutoScrollSnapThrottleMs) {
            return params.lastStreamingSnapMs
        }
        params.listState.scrollToItem(0)
        return nowMs
    }
    params.listState.scrollToItem(0)
    return params.lastStreamingSnapMs
}

@Composable
private fun ChatMessageListLoadOlderEffect(params: ChatMessageListEffectsParams) {
    LaunchedEffect(params.listState, params.state.hasMoreOlderMessages, params.state.isLoadingOlderMessages, params.state.messages.size) {
        snapshotFlow { params.listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { _ ->
                if (!shouldLoadOlderMessages(params)) return@collect
                val lastVisible = params.listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
                val totalItems = params.listState.layoutInfo.totalItemsCount
                if (totalItems > 0 && lastVisible >= totalItems - 3) {
                    params.onLoadOlderMessages()
                }
            }
    }
}

private fun shouldLoadOlderMessages(params: ChatMessageListEffectsParams): Boolean {
    if (!params.state.hasMoreOlderMessages) return false
    if (params.state.isLoadingOlderMessages) return false
    if (params.state.messages.isEmpty()) return false
    return true
}
