package com.letta.mobile.feature.chat.screen.messagelist

import com.letta.mobile.feature.chat.screen.LocalChatShouldDeferHeavyToolCards
import com.letta.mobile.feature.chat.screen.ChatFadeEdgeLength
import com.letta.mobile.feature.chat.screen.ChatFadingEdgesBox
import com.letta.mobile.feature.chat.screen.chatFadeTargetColor
import com.letta.mobile.feature.chat.screen.toChatViewportSnapshot
import com.letta.mobile.ui.chat.render.chatGeometrySignature
import com.letta.mobile.ui.chat.render.toChatRenderItemState
import com.letta.mobile.ui.components.ScrollToBottomFab
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.LocalChatIsPinching
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.chatShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.runtime.ChatViewportFollowPolicy

@Composable
internal fun ChatMessageListBody(
    params: ChatMessageListBodyParams,
    modifier: Modifier = Modifier,
) {
    val renderCallbacks = remember(params.callbacks) {
        ChatMessageRenderCallbacks(
            onSendMessage = params.callbacks.onSendMessage,
            onRerunMessage = params.callbacks.onRerunMessage,
            onSubmitApproval = params.callbacks.onSubmitApproval,
            onToggleRunCollapsed = params.callbacks.onToggleRunCollapsed,
            onToggleReasoningExpanded = params.callbacks.onToggleReasoningExpanded,
            onAttachmentImageTap = params.callbacks.onAttachmentImageTap,
        )
    }
    val itemState = remember(
        params.state.isStreaming,
        params.state.activeApprovalRequestId,
        params.state.collapsedRunIds,
        params.state.expandedReasoningMessageIds,
    ) {
        params.state.toChatRenderItemState()
    }

    ChatMessageListBodyContent(
        params = params,
        renderCallbacks = renderCallbacks,
        itemState = itemState,
        modifier = modifier,
    )
}

@Composable
private fun ChatMessageListBodyContent(
    params: ChatMessageListBodyParams,
    renderCallbacks: ChatMessageRenderCallbacks,
    itemState: com.letta.mobile.ui.chat.render.ChatRenderItemState,
    modifier: Modifier,
) {
    val chatDimens = MaterialTheme.chatDimens
    val chatShapes = MaterialTheme.chatShapes
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val contentWidthPx = with(density) {
            (maxWidth - chatDimens.contentPaddingHorizontal - chatDimens.contentPaddingHorizontal)
                .roundToPx()
                .coerceAtLeast(0)
        }
        val newestMessageId = params.state.messages.lastOrNull()?.id
        val lazyColumnParams = ChatMessageListLazyColumnParams(
            bodyParams = params,
            renderCallbacks = renderCallbacks,
            itemState = itemState,
            conversationId = (
                params.state.conversationState as?
                    com.letta.mobile.ui.chat.render.ConversationState.Ready
                )?.conversationId,
            contentWidthPx = contentWidthPx,
            newestMessageId = newestMessageId,
            density = density,
            layoutDirection = layoutDirection,
            chatDimens = chatDimens,
            chatShapes = chatShapes,
        )
        val activeStreamingGeometryBuckets = rememberActiveStreamingGeometryBuckets(
            ActiveStreamingGeometryInput(
                bodyParams = params,
                itemState = itemState,
                newestMessageId = newestMessageId,
                contentWidthPx = contentWidthPx,
                density = density,
                layoutDirection = layoutDirection,
            ),
        )
        SideEffect {
            params.itemGeometryState.retainStreamingBuckets(activeStreamingGeometryBuckets)
        }

        CompositionLocalProvider(
            LocalChatIsPinching provides params.suppressPinchLayoutAnimations,
            LocalChatFontScale provides params.liveFontScale,
            LocalChatShouldDeferHeavyToolCards provides shouldDeferHeavyToolCards(params),
        ) {
            ChatMessageListLazyColumnContent(lazyColumnParams)
        }

        ChatMessageListScrollFab(params)
        ChatMessageListFontIndicator(params)
    }
}

@Composable
private fun rememberActiveStreamingGeometryBuckets(
    input: ActiveStreamingGeometryInput,
): Set<com.letta.mobile.ui.chat.render.ChatMessageGeometryBucket> {
    val params = input.bodyParams
    return remember(
        params.state.isStreaming,
        input.newestMessageId,
        params.renderItems,
        input.contentWidthPx,
        params.appearance.activeFontScale,
        input.density.density,
        input.density.fontScale,
        input.layoutDirection,
        params.appearance.chatMode,
        params.state.collapsedRunIds,
        params.state.expandedReasoningMessageIds,
        params.state.activeApprovalRequestId,
    ) {
        if (!params.state.isStreaming || input.newestMessageId == null) {
            emptySet()
        } else {
            params.renderItems
                .filter { it.containsMessageId(input.newestMessageId) }
                .map {
                    it.chatGeometrySignature(
                        state = input.itemState,
                        chatMode = params.appearance.chatMode,
                        widthPx = input.contentWidthPx,
                        density = input.density,
                        layoutDirection = input.layoutDirection,
                        activeFontScale = params.appearance.activeFontScale,
                    ).bucket
                }
                .toSet()
        }
    }
}

private fun shouldDeferHeavyToolCards(params: ChatMessageListBodyParams): Boolean {
    // Do not key deferred tool cards on initial hydrate (`isLoadingMessages`):
    // that flips false on Hydrated and morphs every visible card in one frame.
    // Defer only under scroll-pressure paths that already own layout instability.
    return params.state.isLoadingOlderMessages ||
        params.suppressPinchLayoutAnimations
}

@Composable
private fun ChatMessageListLazyColumnContent(params: ChatMessageListLazyColumnParams) {
    val bodyParams = params.bodyParams
    val fadeTargetColor = chatFadeTargetColor(
        chatBackground = bodyParams.appearance.chatBackground,
        fallbackContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    val topFadeLength = chatMessageListTopFadeLength(bodyParams.appearance.topPadding)
    val bottomFadeLength = chatMessageListBottomFadeLength(bodyParams.appearance.bottomPadding)
    val suppressBottomFade = rememberChatMessageListSuppressBottomFade(bodyParams)

    ChatFadingEdgesBox(
        listState = bodyParams.listState,
        targetColor = fadeTargetColor,
        modifier = Modifier.fillMaxSize(),
        topPadding = bodyParams.appearance.topPadding,
        topFadeLength = topFadeLength,
        bottomFadeLength = bottomFadeLength,
        suppressBottom = suppressBottomFade,
    ) {
        ChatMessageListLazyColumn(params)
    }
}

private fun chatMessageListTopFadeLength(topPadding: Dp): Dp {
    return if (topPadding > 0.dp) topPadding + 16.dp else ChatFadeEdgeLength
}

private fun chatMessageListBottomFadeLength(bottomPadding: Dp): Dp {
    return if (bottomPadding > 0.dp) bottomPadding + 48.dp else ChatFadeEdgeLength + 48.dp
}

@Composable
private fun rememberChatMessageListSuppressBottomFade(params: ChatMessageListBodyParams): Boolean {
    val suppressBottomFade by remember {
        derivedStateOf {
            val lastMessage = params.state.messages.lastOrNull()
            val isNearBottom = ChatViewportFollowPolicy.isNearLatest(
                params.listState.toChatViewportSnapshot(params.isUserScrolling, params.renderItems.size),
            )
            isNearBottom && lastMessage?.role == "user" && params.state.isStreaming
        }
    }
    return suppressBottomFade
}

@Composable
private fun ChatMessageListLazyColumn(params: ChatMessageListLazyColumnParams) {
    val bodyParams = params.bodyParams
    LazyColumn(
        state = bodyParams.listState,
        contentPadding = PaddingValues(
            start = params.chatDimens.contentPaddingHorizontal,
            end = params.chatDimens.contentPaddingHorizontal,
            top = LettaSpacing.CARD_GAP + bodyParams.appearance.topPadding,
            bottom = LettaSpacing.CARD_GAP + bodyParams.appearance.bottomPadding,
        ),
        reverseLayout = true,
        modifier = Modifier.graphicsLayer { },
    ) {
        chatMessageListItems(
            ChatMessageListItemsParams(
                renderItems = bodyParams.renderItems,
                isLoadingOlderMessages = bodyParams.state.isLoadingOlderMessages,
                context = ChatMessageListLazyContext(
                    itemState = params.itemState,
                    conversationId = params.conversationId,
                    chatMode = bodyParams.appearance.chatMode,
                    contentWidthPx = params.contentWidthPx,
                    density = params.density,
                    layoutDirection = params.layoutDirection,
                    activeFontScale = bodyParams.appearance.activeFontScale,
                    liveFontScale = bodyParams.liveFontScale,
                    newestMessageId = params.newestMessageId,
                    highlightedMessageId = bodyParams.highlightedMessageId,
                    itemGeometryState = bodyParams.itemGeometryState,
                    pinchFontScaleController = bodyParams.pinchFontScaleController,
                    scaleWindowIndexRange = bodyParams.scaleWindowIndexRange,
                    callbacks = params.renderCallbacks,
                ),
                chatDimens = params.chatDimens,
                chatShapes = params.chatShapes,
            ),
        )
    }
}

@Composable
private fun BoxScope.ChatMessageListScrollFab(params: ChatMessageListBodyParams) {
    ScrollToBottomFab(
        visible = params.showScrollFab,
        onClick = params.onScrollToBottom,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(
                end = LettaSpacing.INNER_PADDING,
                bottom = LettaSpacing.INNER_PADDING + params.appearance.bottomPadding,
            ),
    )
}

@Composable
private fun BoxScope.ChatMessageListFontIndicator(params: ChatMessageListBodyParams) {
    if (!params.showFontIndicator) return
    Text(
        text = "${(params.liveFontScale * 100).toInt()}%",
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .align(Alignment.Center)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                RoundedCornerShape(MaterialTheme.chatShapes.bubbleRadius),
            )
            .padding(
                horizontal = LettaSpacing.INNER_PADDING + LettaSpacing.CARD_GAP,
                vertical = LettaSpacing.INNER_PADDING_SMALL,
            ),
    )
}
