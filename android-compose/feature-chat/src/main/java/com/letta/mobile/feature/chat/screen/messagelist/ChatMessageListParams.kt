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
