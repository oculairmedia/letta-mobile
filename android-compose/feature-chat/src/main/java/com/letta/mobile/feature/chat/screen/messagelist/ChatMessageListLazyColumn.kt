package com.letta.mobile.feature.chat.screen.messagelist

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.ui.chat.render.ChatMessageGeometryState
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.render.chatGeometrySignature
import com.letta.mobile.feature.chat.render.LocalToolCardBodyParentVisible
import com.letta.mobile.feature.chat.screen.RunBlock
import com.letta.mobile.feature.chat.screen.SkillEnvelopeChip
import com.letta.mobile.feature.chat.screen.chatRenderItemSeesLiveScale
import com.letta.mobile.ui.components.DateSeparator
import com.letta.mobile.ui.theme.ChatDimens
import com.letta.mobile.ui.theme.ChatShapes
import com.letta.mobile.ui.theme.LocalChatFontScale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.zoom.PinchScalePreviewController
import java.time.LocalDate

internal data class ChatMessageListLazyContext(
    val state: ChatUiState,
    val renderItems: List<ChatRenderItem>,
    val chatMode: String,
    val contentWidthPx: Int,
    val density: Density,
    val layoutDirection: LayoutDirection,
    val activeFontScale: Float,
    val liveFontScale: Float,
    val newestMessageId: String?,
    val highlightedMessageId: String?,
    val itemGeometryState: ChatMessageGeometryState,
    val pinchFontScaleController: PinchScalePreviewController,
    val scaleWindowIndexRange: IntRange,
    val callbacks: ChatMessageRenderCallbacks,
)

internal fun LazyListScope.chatMessageListItems(
    context: ChatMessageListLazyContext,
    chatDimens: ChatDimens,
    chatShapes: ChatShapes,
) {
    context.renderItems.forEachIndexed { index, renderItem ->
        val prevDate = context.renderItems.getOrNull(index + 1)?.boundaryTimestamp?.take(10)
        val currentDate = renderItem.boundaryTimestamp.take(10)
        val showDate = prevDate != null && prevDate != currentDate

        item(key = renderItem.key, contentType = when (renderItem) {
            is ChatRenderItem.Single -> "single"
            is ChatRenderItem.RunBlock -> "runblock"
            is ChatRenderItem.SkillEnvelopeChip -> "skill-envelope"
        }) {
            ChatMessageListRenderItem(
                params = ChatMessageListRenderItemParams(
                    renderItem = renderItem,
                    index = index,
                    context = context,
                    chatDimens = chatDimens,
                    chatShapes = chatShapes,
                ),
            )
        }

        if (showDate) {
            item(key = "date-${renderItem.key}", contentType = "date") {
                val date = try {
                    LocalDate.parse(currentDate)
                } catch (_: Exception) {
                    null
                }
                if (date != null) {
                    DateSeparator(date = date)
                }
            }
        }
    }

    if (context.state.isLoadingOlderMessages) {
        item(key = "older-loading") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = LettaSpacing.INNER_PADDING_SMALL),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun ChatMessageListRenderItem(params: ChatMessageListRenderItemParams) {
    val renderItem = params.renderItem
    val context = params.context
    if (com.letta.mobile.ui.chat.render.RenderDiagnostics.enabled()) {
        SideEffect {
            com.letta.mobile.ui.chat.render.RenderDiagnostics.onLazyItemComposed(
                conversationId = (context.state.conversationState as? ConversationState.Ready)?.conversationId ?: "<active>",
                key = renderItem.key,
                contentType = when (renderItem) {
                    is ChatRenderItem.Single -> "single"
                    is ChatRenderItem.RunBlock -> "runblock"
                    is ChatRenderItem.SkillEnvelopeChip -> "skill-envelope"
                },
            )
        }
    }
    val geometrySignature = renderItem.chatGeometrySignature(
        state = context.state,
        chatMode = context.chatMode,
        widthPx = context.contentWidthPx,
        density = context.density,
        layoutDirection = context.layoutDirection,
        activeFontScale = context.activeFontScale,
    )
    val isStreamingRenderItem = context.state.isStreaming &&
        context.newestMessageId != null &&
        renderItem.containsMessageId(context.newestMessageId)
    val itemSeesLiveScale = chatRenderItemSeesLiveScale(
        isPinching = context.pinchFontScaleController.isPinching,
        scaleWindowIndexRange = context.scaleWindowIndexRange,
        itemIndex = params.index,
    )
    val perItemFontScale = if (itemSeesLiveScale) context.liveFontScale else context.activeFontScale
    CompositionLocalProvider(
        LocalChatFontScale provides perItemFontScale,
        LocalToolCardBodyParentVisible provides itemSeesLiveScale,
    ) {
        MeasuredChatRenderItem(
            signature = geometrySignature,
            geometryState = context.itemGeometryState,
            isStreaming = isStreamingRenderItem,
        ) {
            ChatMessageListRenderItemBody(
                params = ChatMessageListRenderItemBodyParams(
                    renderItem = renderItem,
                    context = context,
                    chatDimens = params.chatDimens,
                    chatShapes = params.chatShapes,
                    isStreamingRenderItem = isStreamingRenderItem,
                ),
            )
        }
    }
}

@Composable
private fun ChatMessageListRenderItemBody(params: ChatMessageListRenderItemBodyParams) {
    when (val renderItem = params.renderItem) {
        is ChatRenderItem.Single -> ChatMessageListRenderSingleItem(
            renderItem = renderItem,
            context = params.context,
            chatDimens = params.chatDimens,
            isStreamingRenderItem = params.isStreamingRenderItem,
        )
        is ChatRenderItem.SkillEnvelopeChip -> {
            SkillEnvelopeChip(
                slug = renderItem.slug,
                name = renderItem.name,
                description = renderItem.description,
                args = renderItem.args,
                rawContent = renderItem.rawContent,
                chatMode = params.context.chatMode,
                modifier = Modifier.padding(top = params.chatDimens.ungroupedMessageSpacing),
            )
        }
        is ChatRenderItem.RunBlock -> ChatMessageListRenderRunBlockItem(
            params = ChatMessageListRenderRunBlockItemParams(
                renderItem = renderItem,
                context = params.context,
                chatDimens = params.chatDimens,
                chatShapes = params.chatShapes,
                isStreamingRenderItem = params.isStreamingRenderItem,
            ),
        )
    }
}

@Composable
private fun ChatMessageListRenderSingleItem(
    renderItem: ChatRenderItem.Single,
    context: ChatMessageListLazyContext,
    chatDimens: ChatDimens,
    isStreamingRenderItem: Boolean,
) {
    val msg = renderItem.message
    val stableKey = renderItem.stableRunKey
    if (stableKey != null) {
        val runId = renderItem.stableRunId ?: stableKey.removePrefix("run-")
        RunBlock(
            messages = listOf(msg),
            collapsed = runId in context.state.collapsedRunIds,
            onToggleCollapsed = {
                context.itemGeometryState.clearStreamingFloors()
                context.callbacks.onToggleRunCollapsed(runId)
            },
            modifier = Modifier.padding(top = chatDimens.ungroupedMessageSpacing),
            isStreaming = context.state.isStreaming,
            activeApprovalRequestId = context.state.activeApprovalRequestId,
            onApprovalDecision = context.callbacks.onSubmitApproval,
        ) { message, position, rowModifier ->
            RenderChatMessageRow(
                params = RenderChatMessageRowParams(
                    message = message,
                    position = position,
                    context = context,
                    isStreamingRenderItem = isStreamingRenderItem,
                ),
                modifier = rowModifier,
            )
        }
        return
    }
    RenderChatMessageRow(
        params = RenderChatMessageRowParams(
            message = msg,
            position = renderItem.groupPosition,
            context = context,
            isStreamingRenderItem = isStreamingRenderItem,
        ),
    )
}

@Composable
private fun ChatMessageListRenderRunBlockItem(params: ChatMessageListRenderRunBlockItemParams) {
    val renderItem = params.renderItem
    val context = params.context
    val isHighlighted = renderItem.containsMessageId(context.highlightedMessageId.orEmpty())
    val highlightModifier = if (isHighlighted) {
        Modifier.background(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            RoundedCornerShape(params.chatShapes.bubbleRadius),
        )
    } else {
        Modifier
    }
    RunBlock(
        messages = renderItem.messages.map { it.first },
        collapsed = renderItem.runId in context.state.collapsedRunIds,
        onToggleCollapsed = {
            context.itemGeometryState.clearStreamingFloors()
            context.callbacks.onToggleRunCollapsed(renderItem.runId)
        },
        modifier = highlightModifier.padding(top = params.chatDimens.ungroupedMessageSpacing),
        isStreaming = context.state.isStreaming,
        activeApprovalRequestId = context.state.activeApprovalRequestId,
        onApprovalDecision = context.callbacks.onSubmitApproval,
    ) { message, position, rowModifier ->
        RenderChatMessageRow(
            params = RenderChatMessageRowParams(
                message = message,
                position = position,
                context = context,
                isStreamingRenderItem = params.isStreamingRenderItem,
            ),
            modifier = rowModifier,
        )
    }
}

private data class RenderChatMessageRowParams(
    val message: com.letta.mobile.data.model.UiMessage,
    val position: com.letta.mobile.ui.common.GroupPosition,
    val context: ChatMessageListLazyContext,
    val isStreamingRenderItem: Boolean,
)

@Composable
private fun RenderChatMessageRow(
    params: RenderChatMessageRowParams,
    modifier: Modifier = Modifier,
) {
    val message = params.message
    val context = params.context
    RenderChatMessage(
        message = message,
        position = params.position,
        isStreaming = params.isStreamingRenderItem && message.id == context.newestMessageId,
        rerunEnabled = !context.state.isStreaming,
        approvalInFlight = context.state.activeApprovalRequestId == message.approvalRequest?.requestId,
        chatMode = context.chatMode,
        highlightedMessageId = context.highlightedMessageId,
        callbacks = context.callbacks,
        reasoningCollapsed = message.id !in context.state.expandedReasoningMessageIds,
        onToggleReasoning = { context.callbacks.onToggleReasoningExpanded(message.id) },
        modifier = modifier,
    )
}
