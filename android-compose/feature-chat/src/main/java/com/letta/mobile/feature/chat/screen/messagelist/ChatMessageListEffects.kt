package com.letta.mobile.feature.chat.screen.messagelist

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.runtime.ChatViewportFollowPolicy
import com.letta.mobile.feature.chat.screen.ChatAutoScrollSignature
import com.letta.mobile.feature.chat.screen.ChatFadeEdgeLength
import com.letta.mobile.feature.chat.screen.ChatMessageRoles
import com.letta.mobile.feature.chat.screen.StreamingAutoScrollSnapThrottleMs
import com.letta.mobile.feature.chat.screen.calculateLazyIndexForRenderItem
import com.letta.mobile.feature.chat.screen.newestMessageAutoScrollSignature
import com.letta.mobile.feature.chat.screen.shouldForceScrollOnUserSend
import com.letta.mobile.feature.chat.screen.toChatViewportSnapshot
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

@Composable
internal fun ChatMessageListEffects(params: ChatMessageListEffectsParams) {
    ChatMessageListFocusClearEffect(params.listState)
    ChatMessageListConversationResetEffect(
        conversationId = (params.state.conversationState as? ConversationState.Ready)?.conversationId,
        renderItems = params.renderItems,
        listState = params.listState,
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
private fun ChatMessageListConversationResetEffect(
    conversationId: String?,
    renderItems: List<ChatRenderItem>,
    listState: LazyListState,
) {
    LaunchedEffect(conversationId) {
        if (renderItems.isNotEmpty()) {
            listState.scrollToItem(0)
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
        } else if (shouldAutoScrollToLatest(signature, previousSignature, followLatest, params.renderItems.size)) {
            lastStreamingSnapMs = performAutoScrollToLatest(
                listState = params.listState,
                isStreaming = isStreamingForAutoScroll,
                lastStreamingSnapMs = lastStreamingSnapMs,
            )
        }

        lastAutoScrollSignature.value = signature
    }
}

private fun shouldAutoScrollToLatest(
    signature: ChatAutoScrollSignature,
    previousSignature: ChatAutoScrollSignature?,
    followLatest: Boolean,
    renderItemCount: Int,
): Boolean {
    if (!ChatViewportFollowPolicy.shouldAutoFollow(followLatest, renderItemCount)) return false
    return signature.role != ChatMessageRoles.User ||
        signature.messageId != previousSignature?.messageId
}

private suspend fun performAutoScrollToLatest(
    listState: LazyListState,
    isStreaming: Boolean,
    lastStreamingSnapMs: Long,
): Long {
    val nowMs = System.currentTimeMillis()
    if (isStreaming) {
        if (nowMs - lastStreamingSnapMs < StreamingAutoScrollSnapThrottleMs) {
            return lastStreamingSnapMs
        }
        listState.scrollToItem(0)
        return nowMs
    }
    listState.scrollToItem(0)
    return lastStreamingSnapMs
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

@Composable
private fun ChatMessageListScrollToMessageEffect(params: ChatMessageListEffectsParams) {
    LaunchedEffect(params.scrollToMessageId, params.renderItems.size) {
        val target = resolveScrollToMessageTarget(
            scrollToMessageId = params.scrollToMessageId,
            hasScrolledToTarget = params.hasScrolledToTarget,
            renderItems = params.renderItems,
        ) ?: return@LaunchedEffect

        params.listState.scrollToItem(
            calculateLazyIndexForRenderItem(
                targetRenderIndex = target.renderIndex,
                renderItems = target.renderItems,
            ),
        )
        params.onHighlightedMessageIdChange(target.messageId)
        params.onHasScrolledToTargetChange(true)
        delay(2000)
        params.onHighlightedMessageIdChange(null)
    }
}

private data class ResolvedScrollToMessageTarget(
    val messageId: String,
    val renderIndex: Int,
    val renderItems: List<ChatRenderItem>,
)

private fun resolveScrollToMessageTarget(
    scrollToMessageId: String?,
    hasScrolledToTarget: Boolean,
    renderItems: List<ChatRenderItem>,
): ResolvedScrollToMessageTarget? {
    if (!shouldAttemptScrollToMessage(scrollToMessageId, hasScrolledToTarget)) return null
    if (renderItems.isEmpty()) return null
    val targetIdx = findRenderIndexForMessage(scrollToMessageId!!, renderItems)
    if (targetIdx < 0) return null
    return ResolvedScrollToMessageTarget(
        messageId = scrollToMessageId,
        renderIndex = targetIdx,
        renderItems = renderItems,
    )
}

private fun shouldAttemptScrollToMessage(
    scrollToMessageId: String?,
    hasScrolledToTarget: Boolean,
): Boolean {
    if (scrollToMessageId == null) return false
    if (hasScrolledToTarget) return false
    return true
}

private fun findRenderIndexForMessage(
    messageId: String,
    renderItems: List<ChatRenderItem>,
): Int = renderItems.indexOfFirst { it.containsMessageId(messageId) }

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
