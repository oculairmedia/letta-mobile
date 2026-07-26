package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.feature.chat.subagent.SubagentTodoSheetTarget
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class ChatScreenLayoutLocalState(
    val subagentNavigationScope: CoroutineScope,
    val currentConversationId: String?,
    val tappedSubagentTarget: SubagentTodoSheetTarget?,
    val onTappedSubagentTargetChange: (SubagentTodoSheetTarget?) -> Unit,
    val openSubagentTarget: (SubagentTodoSheetTarget) -> Unit,
    val imageViewerState: Pair<ImmutableList<UiImageAttachment>, Int>?,
    val onImageViewerStateChange: (Pair<ImmutableList<UiImageAttachment>, Int>?) -> Unit,
    val bottomPaddingDp: Dp,
    val onComposerHeightChange: (Dp) -> Unit,
    val contentCallbacks: ChatContentCallbacks,
)

@Composable
internal fun rememberChatScreenLayoutLocalState(params: ChatScreenLayoutParams): ChatScreenLayoutLocalState {
    val subagentNavigationScope = rememberCoroutineScope()
    val currentConversationId = params.viewModel.conversationId?.value
    var tappedSubagentTarget by remember { mutableStateOf<SubagentTodoSheetTarget?>(null) }
    var imageViewerState by remember {
        mutableStateOf<Pair<ImmutableList<UiImageAttachment>, Int>?>(null)
    }
    var composerHeightDp by remember { mutableStateOf(0.dp) }
    val bottomPaddingDp = composerHeightDp + params.bottomInsetDp

    val openImageViewer: (List<UiImageAttachment>, Int) -> Unit = { attachments, index ->
        imageViewerState = attachments.toImmutableList() to index
    }
    val contentCallbacks = remember(params.viewModel, openImageViewer, params.onActiveFontScaleChange) {
        ChatContentCallbacks(
            onSendMessage = { params.viewModel.sendMessage(it) },
            onRerunMessage = { params.viewModel.rerunMessage(it) },
            onLoadOlderMessages = { params.viewModel.loadOlderMessages() },
            onSubmitApproval = { requestId, toolCallIds, approve, reason ->
                params.viewModel.submitApproval(requestId, toolCallIds, approve, reason)
            },
            onToggleRunCollapsed = params.viewModel::toggleRunCollapsed,
            onToggleReasoningExpanded = params.viewModel::toggleReasoningExpanded,
            onA2uiAction = params.viewModel::submitA2uiAction,
            onDismissA2uiSurface = params.viewModel::dismissA2uiSurface,
            onAttachmentImageTap = openImageViewer,
            onActiveFontScaleChange = params.onActiveFontScaleChange,
            onFontScaleChange = { params.viewModel.setChatFontScale(it) },
        )
    }
    val openSubagentTarget: (SubagentTodoSheetTarget) -> Unit = remember(
        params.resolvedSubagentSource,
        subagentNavigationScope,
    ) {
        { target ->
            tappedSubagentTarget = target
            if (target.subagentConversationId == null) {
                subagentNavigationScope.launch {
                    val subagent = params.resolvedSubagentSource.resolveSubagent(target.toolCallId).getOrNull()
                    val agentId = target.subagentAgentId ?: subagent?.subagentAgentId
                    val conversationId = subagent?.let {
                        params.resolvedSubagentSource.resolveConversationId(it).getOrNull()
                    }
                    if (agentId != null && conversationId != null) {
                        tappedSubagentTarget = target.copy(
                            subagentAgentId = agentId,
                            subagentConversationId = conversationId,
                        )
                    }
                }
            }
        }
    }

    return ChatScreenLayoutLocalState(
        subagentNavigationScope = subagentNavigationScope,
        currentConversationId = currentConversationId,
        tappedSubagentTarget = tappedSubagentTarget,
        onTappedSubagentTargetChange = { tappedSubagentTarget = it },
        openSubagentTarget = openSubagentTarget,
        imageViewerState = imageViewerState,
        onImageViewerStateChange = { imageViewerState = it },
        bottomPaddingDp = bottomPaddingDp,
        onComposerHeightChange = { composerHeightDp = it },
        contentCallbacks = contentCallbacks,
    )
}
