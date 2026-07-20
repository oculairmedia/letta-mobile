package com.letta.mobile.feature.chat.screen

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.runtime.ChatViewportFollowPolicy
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.screen.messagelist.ChatLoadPressureSummary
import com.letta.mobile.feature.chat.screen.messagelist.ChatMessageListBodyParams
import com.letta.mobile.feature.chat.screen.messagelist.ChatMessageListEffectsParams
import com.letta.mobile.feature.chat.screen.messagelist.ChatMessageListAppearance
import com.letta.mobile.feature.chat.screen.messagelist.ChatMessageListBody
import com.letta.mobile.feature.chat.screen.messagelist.ChatMessageListCallbacks
import com.letta.mobile.feature.chat.screen.messagelist.ChatMessageListEffects
import com.letta.mobile.feature.chat.screen.messagelist.ChatPinchGestureBoxParams
import com.letta.mobile.feature.chat.screen.messagelist.ChatMessageListPinchGestureBox
import com.letta.mobile.feature.chat.screen.messagelist.ChatMessageListPinchIndicatorEffects
import com.letta.mobile.feature.chat.screen.messagelist.ChatPinchFrameBudgetSampler
import com.letta.mobile.ui.chat.render.ChatMessageGeometryState
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.ui.zoom.PinchScalePreviewController
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun ChatMessageList(
    state: ChatUiState,
    renderItems: List<ChatRenderItem>,
    chatMode: String,
    scrollToMessageId: String?,
    activeFontScale: Float,
    onActiveFontScaleChange: (Float) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onLoadOlderMessages: () -> Unit,
    onSendMessage: (String) -> Unit,
    onRerunMessage: (UiMessage) -> Unit,
    onSubmitApproval: (String, List<String>, Boolean, String?) -> Unit,
    onToggleRunCollapsed: (String) -> Unit,
    onToggleReasoningExpanded: (String) -> Unit,
    onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)?,
    modifier: Modifier = Modifier,
    chatBackground: ChatBackground = ChatBackground.Default,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
) {
    val callbacks = ChatMessageListCallbacks(
        onActiveFontScaleChange = onActiveFontScaleChange,
        onFontScaleChange = onFontScaleChange,
        onLoadOlderMessages = onLoadOlderMessages,
        onSendMessage = onSendMessage,
        onRerunMessage = onRerunMessage,
        onSubmitApproval = onSubmitApproval,
        onToggleRunCollapsed = onToggleRunCollapsed,
        onToggleReasoningExpanded = onToggleReasoningExpanded,
        onAttachmentImageTap = onAttachmentImageTap,
    )
    val appearance = ChatMessageListAppearance(
        chatMode = chatMode,
        chatBackground = chatBackground,
        topPadding = topPadding,
        bottomPadding = bottomPadding,
        activeFontScale = activeFontScale,
        scrollToMessageId = scrollToMessageId,
    )

    val listState = rememberLazyListState()
    val isUserScrolling by listState.interactionSource.collectIsDraggedAsState()
    val scope = rememberCoroutineScope()
    val currentRenderItems by rememberUpdatedState(renderItems)
    val itemGeometryState = remember { ChatMessageGeometryState() }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var hasScrolledToTarget by remember { mutableStateOf(false) }
    var showFontIndicator by remember { mutableStateOf(false) }
    var pinchTick by remember { mutableStateOf(0L) }
    var pinchAnimationSuppressionTick by remember { mutableStateOf(0L) }
    var suppressPinchLayoutAnimations by remember { mutableStateOf(false) }
    val pinchFontScaleController = remember {
        PinchScalePreviewController(minScale = 0.7f, maxScale = 1.6f, step = 0.02f)
    }
    val pinchFrameBudgetSampler = remember { ChatPinchFrameBudgetSampler() }

    DisposableEffect(Unit) {
        onDispose { pinchFrameBudgetSampler.cancel() }
    }

    ChatMessageListEffects(
        state = state,
        renderItems = renderItems,
        listState = listState,
        isUserScrolling = isUserScrolling,
        scrollToMessageId = scrollToMessageId,
        onLoadOlderMessages = callbacks.onLoadOlderMessages,
        onHighlightedMessageIdChange = { highlightedMessageId = it },
        hasScrolledToTarget = hasScrolledToTarget,
        onHasScrolledToTargetChange = { hasScrolledToTarget = it },
    )

    val liveFontScale = if (pinchFontScaleController.isPinching) {
        pinchFontScaleController.effectiveScale
    } else {
        activeFontScale
    }
    SideEffect {
        pinchFontScaleController.syncCommittedScale(activeFontScale)
    }

    val scaleWindowIndexRange: IntRange = if (pinchFontScaleController.isPinching) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) {
            IntRange.EMPTY
        } else {
            val visibleCount = visible.size
            val scaleRatio = if (activeFontScale > 0f) liveFontScale / activeFontScale else 1f
            val marginItems = (visibleCount * scaleRatio).toInt().coerceAtLeast(2)
            val firstIdx = visible.first().index
            val lastIdx = visible.last().index
            (firstIdx - marginItems).coerceAtLeast(0)..(lastIdx + marginItems)
        }
    } else {
        IntRange.EMPTY
    }

    ChatMessageListPinchIndicatorEffects(
        pinchTick = pinchTick,
        pinchAnimationSuppressionTick = pinchAnimationSuppressionTick,
        isPinching = pinchFontScaleController.isPinching,
        onShowFontIndicator = { showFontIndicator = it },
        onSuppressPinchLayoutAnimations = { suppressPinchLayoutAnimations = it },
    )

    val loadPressureSummary = remember(
        state.messages.size,
        state.isStreaming,
        state.isLoadingMessages,
        state.isLoadingOlderMessages,
        renderItems.size,
    ) {
        ChatLoadPressureSummary(
            messageCount = state.messages.size,
            renderItemCount = renderItems.size,
            isStreaming = state.isStreaming,
            isLoadingMessages = state.isLoadingMessages,
            isLoadingOlderMessages = state.isLoadingOlderMessages,
            toolCardCount = 0,
        )
    }
    val currentLoadPressureSummary by rememberUpdatedState(loadPressureSummary)

    val showScrollFab by remember(renderItems.size) {
        derivedStateOf {
            ChatViewportFollowPolicy.shouldShowScrollToLatest(
                listState.toChatViewportSnapshot(isUserScrolling, renderItems.size),
            )
        }
    }

    ChatMessageListPinchGestureBox(
        params = ChatPinchGestureBoxParams(
            listState = listState,
            activeFontScale = activeFontScale,
            currentRenderItems = currentRenderItems,
            currentLoadPressureSummary = currentLoadPressureSummary,
            callbacks = callbacks,
            pinchFontScaleController = pinchFontScaleController,
            pinchFrameBudgetSampler = pinchFrameBudgetSampler,
            onPinchTick = { pinchTick = it },
            onPinchAnimationSuppressionTick = { pinchAnimationSuppressionTick = it },
            onSuppressPinchLayoutAnimations = { suppressPinchLayoutAnimations = it },
            scope = scope,
        ),
        modifier = modifier,
    ) {
        ChatMessageListBody(
            params = ChatMessageListBodyParams(
                state = state,
                renderItems = renderItems,
                appearance = appearance,
                callbacks = callbacks,
                listState = listState,
                isUserScrolling = isUserScrolling,
                liveFontScale = liveFontScale,
                pinchFontScaleController = pinchFontScaleController,
                scaleWindowIndexRange = scaleWindowIndexRange,
                itemGeometryState = itemGeometryState,
                highlightedMessageId = highlightedMessageId,
                showScrollFab = showScrollFab,
                suppressPinchLayoutAnimations = suppressPinchLayoutAnimations,
                onScrollToBottom = { scope.launch { listState.animateScrollToItem(0) } },
                showFontIndicator = showFontIndicator,
            ),
        )
    }
}
