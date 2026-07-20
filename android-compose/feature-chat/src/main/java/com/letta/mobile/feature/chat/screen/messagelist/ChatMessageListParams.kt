package com.letta.mobile.feature.chat.screen.messagelist

import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.theme.ChatBackground
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class ChatMessageListCallbacks(
    val onActiveFontScaleChange: (Float) -> Unit,
    val onFontScaleChange: (Float) -> Unit,
    val onLoadOlderMessages: () -> Unit,
    val onSendMessage: (String) -> Unit,
    val onRerunMessage: (UiMessage) -> Unit,
    val onSubmitApproval: (String, List<String>, Boolean, String?) -> Unit,
    val onToggleRunCollapsed: (String) -> Unit,
    val onToggleReasoningExpanded: (String) -> Unit,
    val onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)?,
)

internal data class ChatMessageListAppearance(
    val chatMode: String,
    val chatBackground: ChatBackground = ChatBackground.Default,
    val topPadding: Dp = 0.dp,
    val bottomPadding: Dp = 0.dp,
    val activeFontScale: Float,
    val scrollToMessageId: String? = null,
)

internal data class ChatMessageRenderCallbacks(
    val onSendMessage: (String) -> Unit,
    val onRerunMessage: (UiMessage) -> Unit,
    val onSubmitApproval: (String, List<String>, Boolean, String?) -> Unit,
    val onToggleRunCollapsed: (String) -> Unit,
    val onToggleReasoningExpanded: (String) -> Unit,
    val onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)?,
)

internal data class ChatMessageListEffectsParams(
    val state: com.letta.mobile.ui.chat.render.ChatUiState,
    val renderItems: List<com.letta.mobile.data.chat.projection.ChatRenderItem>,
    val listState: androidx.compose.foundation.lazy.LazyListState,
    val isUserScrolling: Boolean,
    val scrollToMessageId: String?,
    val onLoadOlderMessages: () -> Unit,
    val onHighlightedMessageIdChange: (String?) -> Unit,
    val hasScrolledToTarget: Boolean,
    val onHasScrolledToTargetChange: (Boolean) -> Unit,
)

internal data class ChatMessageListLazyColumnParams(
    val bodyParams: ChatMessageListBodyParams,
    val renderCallbacks: ChatMessageRenderCallbacks,
    val contentWidthPx: Int,
    val newestMessageId: String?,
    val density: androidx.compose.ui.unit.Density,
    val layoutDirection: androidx.compose.ui.unit.LayoutDirection,
    val chatDimens: com.letta.mobile.ui.theme.ChatDimens,
    val chatShapes: com.letta.mobile.ui.theme.ChatShapes,
)

internal data class ActiveStreamingGeometryInput(
    val bodyParams: ChatMessageListBodyParams,
    val newestMessageId: String?,
    val contentWidthPx: Int,
    val density: androidx.compose.ui.unit.Density,
    val layoutDirection: androidx.compose.ui.unit.LayoutDirection,
)

internal data class ChatMessageListBodyParams(
    val state: com.letta.mobile.ui.chat.render.ChatUiState,
    val renderItems: List<com.letta.mobile.data.chat.projection.ChatRenderItem>,
    val appearance: ChatMessageListAppearance,
    val callbacks: ChatMessageListCallbacks,
    val listState: androidx.compose.foundation.lazy.LazyListState,
    val isUserScrolling: Boolean,
    val liveFontScale: Float,
    val pinchFontScaleController: com.letta.mobile.ui.zoom.PinchScalePreviewController,
    val scaleWindowIndexRange: IntRange,
    val itemGeometryState: com.letta.mobile.ui.chat.render.ChatMessageGeometryState,
    val highlightedMessageId: String?,
    val showScrollFab: Boolean,
    val suppressPinchLayoutAnimations: Boolean,
    val onScrollToBottom: () -> Unit,
    val showFontIndicator: Boolean,
)
