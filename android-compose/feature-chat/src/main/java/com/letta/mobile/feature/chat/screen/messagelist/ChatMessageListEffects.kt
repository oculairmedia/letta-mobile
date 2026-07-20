package com.letta.mobile.feature.chat.screen.messagelist

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.runtime.ChatViewportFollowPolicy
import com.letta.mobile.feature.chat.screen.ChatAutoScrollSignature
import com.letta.mobile.feature.chat.screen.ChatFadeEdgeLength
import com.letta.mobile.feature.chat.screen.StreamingAutoScrollSnapThrottleMs
import com.letta.mobile.feature.chat.screen.calculateLazyIndexForRenderItem
import com.letta.mobile.feature.chat.screen.newestMessageAutoScrollSignature
import com.letta.mobile.feature.chat.screen.shouldForceScrollOnUserSend
import com.letta.mobile.feature.chat.screen.toChatViewportSnapshot
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.letta.mobile.feature.chat.screen.ChatMotion
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow

@Composable
internal fun ChatMessageListEffects(
    state: ChatUiState,
    renderItems: List<ChatRenderItem>,
    listState: LazyListState,
    isUserScrolling: Boolean,
    scrollToMessageId: String?,
    onLoadOlderMessages: () -> Unit,
    onHighlightedMessageIdChange: (String?) -> Unit,
    hasScrolledToTarget: Boolean,
    onHasScrolledToTargetChange: (Boolean) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current

    LaunchedEffect(listState.interactionSource) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                focusManager.clearFocus()
            }
        }
    }

    val autoScrollSignature = newestMessageAutoScrollSignature(state.messages)
    val isStreamingForAutoScroll by rememberUpdatedState(state.isStreaming)
    val conversationId = (state.conversationState as? ConversationState.Ready)?.conversationId

    var lastStreamingSnapMs by remember { mutableStateOf(0L) }
    var followLatest by remember(conversationId) { mutableStateOf(true) }

    LaunchedEffect(conversationId) {
        followLatest = true
        if (renderItems.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(listState, renderItems.size) {
        snapshotFlow { listState.toChatViewportSnapshot(isUserScrolling, renderItems.size) }
            .distinctUntilChanged()
            .collect { snapshot ->
                followLatest = ChatViewportFollowPolicy.nextFollowModeAfterScroll(
                    currentFollowMode = followLatest,
                    snapshot = snapshot,
                )
            }
    }

    val lastAutoScrollSignature = remember { mutableStateOf<ChatAutoScrollSignature?>(null) }

    LaunchedEffect(autoScrollSignature, isStreamingForAutoScroll, renderItems.size) {
        val signature = autoScrollSignature ?: return@LaunchedEffect
        val previousSignature = lastAutoScrollSignature.value

        if (shouldForceScrollOnUserSend(signature, previousSignature?.messageId)) {
            followLatest = true
            val sendScrollOffset = with(density) { -ChatFadeEdgeLength.roundToPx() }
            listState.animateScrollToItem(0, sendScrollOffset)
        } else if (
            ChatViewportFollowPolicy.shouldAutoFollow(followLatest, renderItems.size) &&
            (signature.role != "user" || signature.messageId != previousSignature?.messageId)
        ) {
            val nowMs = System.currentTimeMillis()
            if (isStreamingForAutoScroll) {
                if (nowMs - lastStreamingSnapMs >= StreamingAutoScrollSnapThrottleMs) {
                    lastStreamingSnapMs = nowMs
                    listState.scrollToItem(0)
                }
            } else {
                listState.scrollToItem(0)
            }
        }

        lastAutoScrollSignature.value = signature
    }

    LaunchedEffect(listState, state.hasMoreOlderMessages, state.isLoadingOlderMessages, state.messages.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { _ ->
                if (!state.hasMoreOlderMessages || state.isLoadingOlderMessages || state.messages.isEmpty()) {
                    return@collect
                }
                val lastVisible = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
                val totalItems = listState.layoutInfo.totalItemsCount
                if (totalItems > 0 && lastVisible >= totalItems - 3) {
                    onLoadOlderMessages()
                }
            }
    }

    LaunchedEffect(scrollToMessageId, renderItems.size) {
        if (scrollToMessageId == null || hasScrolledToTarget || renderItems.isEmpty()) return@LaunchedEffect
        val targetIdx = renderItems.indexOfFirst { it.containsMessageId(scrollToMessageId) }
        if (targetIdx >= 0) {
            listState.scrollToItem(
                calculateLazyIndexForRenderItem(
                    targetRenderIndex = targetIdx,
                    renderItems = renderItems,
                ),
            )
            onHighlightedMessageIdChange(scrollToMessageId)
            onHasScrolledToTargetChange(true)
            delay(2000)
            onHighlightedMessageIdChange(null)
        }
    }
}

@Composable
internal fun ChatMessageListPinchIndicatorEffects(
    pinchTick: Long,
    pinchAnimationSuppressionTick: Long,
    isPinching: Boolean,
    onShowFontIndicator: (Boolean) -> Unit,
    onSuppressPinchLayoutAnimations: (Boolean) -> Unit,
) {
    LaunchedEffect(pinchTick) {
        if (pinchTick > 0) {
            onShowFontIndicator(true)
            delay(1000)
            onShowFontIndicator(false)
        }
    }

    LaunchedEffect(pinchAnimationSuppressionTick) {
        if (pinchAnimationSuppressionTick > 0) {
            delay(ChatMotion.ContentSizeMillis.toLong())
            if (!isPinching) {
                onSuppressPinchLayoutAnimations(false)
            }
        }
    }
}
