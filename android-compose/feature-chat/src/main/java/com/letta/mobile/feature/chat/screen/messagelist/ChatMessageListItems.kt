package com.letta.mobile.feature.chat.screen.messagelist

import com.letta.mobile.ui.theme.LettaCodeFont
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.chat.render.ChatMessageGeometryState
import com.letta.mobile.ui.chat.render.ChatRenderItemGeometrySignature
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.letta.mobile.feature.chat.screen.ChatMessageItem
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.LocalChatIsPinching
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.chatShapes

@Composable
internal fun MeasuredChatRenderItem(
    signature: ChatRenderItemGeometrySignature,
    geometryState: ChatMessageGeometryState,
    isStreaming: Boolean,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val isPinching = LocalChatIsPinching.current
    val applyFloor = isStreaming && !isPinching
    val heightFloorPx = if (applyFloor) geometryState.heightFloorFor(signature, isStreaming) else 0
    val minHeightModifier = if (heightFloorPx > 0) {
        Modifier.heightIn(min = with(density) { heightFloorPx.toDp() })
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(minHeightModifier)
            .onSizeChanged { size ->
                if (!isPinching) {
                    geometryState.recordMeasuredHeight(
                        signature = signature,
                        heightPx = size.height,
                        isStreaming = isStreaming,
                    )
                }
            },
    ) {
        content()
    }
}

@Composable
internal fun RenderChatMessage(
    message: UiMessage,
    position: GroupPosition,
    isStreaming: Boolean,
    rerunEnabled: Boolean,
    approvalInFlight: Boolean,
    chatMode: String,
    highlightedMessageId: String?,
    callbacks: ChatMessageRenderCallbacks,
    reasoningCollapsed: Boolean = false,
    onToggleReasoning: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val spacingBelow = when {
        position == GroupPosition.Middle || position == GroupPosition.Last -> MaterialTheme.chatDimens.groupedMessageSpacing
        else -> MaterialTheme.chatDimens.ungroupedMessageSpacing
    }
    val spacingAbove = if (message.isReasoning) LettaSpacing.INNER_PADDING_SMALL else LettaSpacing.NONE
    val isHighlighted = message.id == highlightedMessageId
    val highlightModifier = if (isHighlighted) {
        Modifier.background(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            RoundedCornerShape(MaterialTheme.chatShapes.bubbleRadius),
        )
    } else {
        Modifier
    }
    if (chatMode == "debug") {
        DebugMessageCard(
            message = message,
            modifier = modifier.then(highlightModifier).padding(top = spacingBelow, bottom = spacingAbove),
        )
    } else {
        ChatMessageItem(
            message = message,
            groupPosition = position,
            isStreaming = isStreaming,
            reasoningCollapsed = reasoningCollapsed,
            onToggleReasoning = onToggleReasoning,
            onGeneratedUiMessage = callbacks.onSendMessage,
            onRerunMessage = callbacks.onRerunMessage,
            rerunEnabled = rerunEnabled,
            onApprovalDecision = { requestId, toolCallIds, approve, reason ->
                callbacks.onSubmitApproval(requestId, toolCallIds, approve, reason)
            },
            approvalInFlight = approvalInFlight,
            onAttachmentImageTap = callbacks.onAttachmentImageTap,
            modifier = modifier.then(highlightModifier).padding(top = spacingBelow, bottom = spacingAbove),
        )
    }
}

@Composable
private fun DebugMessageCard(
    message: UiMessage,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(LettaSpacing.CARD_GAP)) {
            Text(
                text = "${message.role} | ${message.id}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(LettaSpacing.CARD_GROUP_ITEM_GAP + LettaSpacing.CARD_GROUP_ITEM_GAP))
            Text(
                text = buildString {
                    append("content: ${message.content.take(200)}")
                    if (message.content.length > 200) append("...")
                    if (message.isReasoning) append("\nisReasoning: true")
                    message.toolCalls?.forEach { tc ->
                        append("\ntool: ${tc.name}(${tc.arguments.take(100)})")
                        tc.result?.let { append("\nresult: ${it.take(100)}") }
                    }
                },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = LettaCodeFont,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
