package com.letta.mobile.feature.chat.screen

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.theme.ChatBackground
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class ChatScreenNavigationCallbacks(
    val onBugCommand: (() -> Unit)? = null,
    val onViewSubagentConversation: ((String, String) -> Unit)? = null,
)

internal data class ChatContentCallbacks(
    val onSendMessage: (String) -> Unit,
    val onRerunMessage: (UiMessage) -> Unit,
    val onLoadOlderMessages: () -> Unit,
    val onSubmitApproval: (String, List<String>, Boolean, String?) -> Unit,
    val onToggleRunCollapsed: (String) -> Unit,
    val onToggleReasoningExpanded: (String) -> Unit,
    val onA2uiAction: (A2uiAction) -> Unit = {},
    val onDismissA2uiSurface: (String) -> Unit = {},
    val onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)?,
    val onActiveFontScaleChange: (Float) -> Unit = {},
    val onFontScaleChange: (Float) -> Unit = {},
)

internal data class ChatContentAppearance(
    val chatMode: String = "simple",
    val chatBackground: ChatBackground = ChatBackground.Default,
    val topPadding: Dp = 0.dp,
    val bottomPadding: Dp = 0.dp,
    val activeFontScale: Float = 1f,
    val scrollToMessageId: String? = null,
)

internal data class GoalStatusCallbacks(
    val onRefresh: () -> Unit,
    val onContinue: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onComplete: () -> Unit,
    val onClear: () -> Unit,
)
