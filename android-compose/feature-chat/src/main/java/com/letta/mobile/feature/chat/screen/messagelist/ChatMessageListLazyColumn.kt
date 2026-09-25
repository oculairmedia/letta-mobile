package com.letta.mobile.feature.chat.screen.messagelist

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.ui.chat.render.ChatMessageGeometryState
import com.letta.mobile.ui.chat.render.ChatRenderItemState
import com.letta.mobile.ui.chat.render.chatGeometrySignature
import com.letta.mobile.feature.chat.render.LocalToolCardBodyParentVisible
import com.letta.mobile.feature.chat.screen.RunBlock
import com.letta.mobile.feature.chat.screen.chatRenderItemSeesLiveScale
import com.letta.mobile.ui.components.DateSeparator
import com.letta.mobile.ui.mascot.MascotLoading
import com.letta.mobile.ui.theme.ChatDimens
import com.letta.mobile.ui.theme.ChatShapes
import com.letta.mobile.ui.theme.TimelineZoomScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.zoom.PinchScalePreviewController
import java.time.LocalDate

@Immutable
internal data class ChatMessageListLazyContext(
    val itemState: ChatRenderItemState,
    val conversationId: String?,
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

@Immutable
internal data class ChatMessageListItemsParams(
    val renderItems: List<ChatRenderItem>,
    val isLoadingOlderMessages: Boolean,
    /** Whose history is loading: the older-messages row shows this agent's mascot at work. */
    val agentId: String?,
    val context: ChatMessageListLazyContext,
    val chatDimens: ChatDimens,
    val chatShapes: ChatShapes,
)

internal fun LazyListScope.chatMessageListItems(params: ChatMessageListItemsParams) {
    params.renderItems.forEachIndexed { index, renderItem ->
        val prevDate = params.renderItems.getOrNull(index + 1)?.boundaryTimestamp?.take(10)
        val currentDate = renderItem.boundaryTimestamp.take(10)
        val showDate = prevDate != null && prevDate != currentDate

        item(key = renderItem.key, contentType = when (renderItem) {
            is ChatRenderItem.Single -> "single"
            is ChatRenderItem.RunBlock -> "runblock"
        }) {
            ChatMessageListRenderItem(
                params = ChatMessageListRenderItemParams(
                    renderItem = renderItem,
                    index = index,
                    context = params.context,
                    chatDimens = params.chatDimens,
                    chatShapes = params.chatShapes,
                ),
                modifier = Modifier.animateItem(),
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

    if (params.isLoadingOlderMessages) {
        item(key = "older-loading") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = LettaSpacing.INNER_PADDING_SMALL),
                contentAlignment = Alignment.Center,
            ) {
                MascotLoading(params.agentId)
            }
        }
    }
}

@Composable
internal fun ChatMessageListRenderItem(
    params: ChatMessageListRenderItemParams,
    modifier: Modifier = Modifier,
) {
    val renderItem = params.renderItem
    val context = params.context
    if (com.letta.mobile.ui.chat.render.RenderDiagnostics.enabled()) {
        SideEffect {
            com.letta.mobile.ui.chat.render.RenderDiagnostics.onLazyItemComposed(
                conversationId = context.conversationId ?: "<active>",
                key = renderItem.key,
                contentType = when (renderItem) {
                    is ChatRenderItem.Single -> "single"
                    is ChatRenderItem.RunBlock -> "runblock"
                },
            )
        }
    }
    val geometrySignature = renderItem.chatGeometrySignature(
        state = context.itemState,
        chatMode = context.chatMode,
        widthPx = context.contentWidthPx,
        density = context.density,
        layoutDirection = context.layoutDirection,
        activeFontScale = context.activeFontScale,
    )
    val isStreamingRenderItem = context.itemState.isStreaming &&
        context.newestMessageId != null &&
        renderItem.containsMessageId(context.newestMessageId)
    val itemSeesLiveScale = chatRenderItemSeesLiveScale(
        isPinching = context.pinchFontScaleController.isPinching,
        scaleWindowIndexRange = context.scaleWindowIndexRange,
        itemIndex = params.index,
    )
    val perItemFontScale = if (itemSeesLiveScale) context.liveFontScale else context.activeFontScale
    // The row's zoom scope owns both the scale and the styles built from it, so text inside a row
    // tracks the gesture whether it reads chatTypography or scales a Material style itself.
    TimelineZoomScope(perItemFontScale) {
        CompositionLocalProvider(LocalToolCardBodyParentVisible provides itemSeesLiveScale) {
            // Markdown also reflows after measurement. Let content own its height;
            // a cached outer minimum can keep blank space after the text shrinks.
            MeasuredChatRenderItem(
                signature = geometrySignature,
                geometryState = context.itemGeometryState,
                applyCachedMinHeight = false,
                // The signature carries the committed scale, so while the live scale differs the
                // cached height describes a size this row is no longer drawn at.
                scaleIsTransient = perItemFontScale != context.activeFontScale,
                modifier = modifier,
            ) {
                ChatMessageListRenderItemBody(
                    params = ChatMessageListRenderItemBodyParams(
                        renderItem = renderItem,
                        context = context,
                        chatDimens = params.chatDimens,
                        chatShapes = params.chatShapes,
                        isStreamingRenderItem = isStreamingRenderItem,
                        showTimestamp = params.index == 0,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ChatMessageListRenderItemBody(params: ChatMessageListRenderItemBodyParams) {
    // The live and settled copies of one run can differ in shape - a lone reply holding its run's
    // key live, a grouped block once settled. Rendering every run from this single call site lets
    // Compose update the RunBlock across that swap instead of disposing it and composing a new one,
    // which is what made the run disclosure snap (letta-mobile-qygvv.20).
    val run = params.runParams()
    if (run != null) {
        ChatMessageListRenderRunItem(run)
        return
    }
    val single = params.renderItem as? ChatRenderItem.Single ?: return
    RenderChatMessageRow(
        params = RenderChatMessageRowParams(
            message = single.message,
            position = single.groupPosition,
            context = params.context,
            isStreamingRenderItem = params.isStreamingRenderItem,
            showTimestamp = params.showTimestamp,
        ),
    )
}

private fun ChatMessageListRenderItemBodyParams.runParams(): ChatMessageListRenderRunParams? =
    when (val item = renderItem) {
        is ChatRenderItem.RunBlock -> ChatMessageListRenderRunParams(
            runId = item.runId,
            messages = item.messages.map { it.first },
            topPadding = if (item.messages.all { !it.first.toolCalls.isNullOrEmpty() }) {
                chatDimens.groupedMessageSpacing
            } else {
                chatDimens.ungroupedMessageSpacing
            },
            highlighted = item.containsMessageId(context.highlightedMessageId.orEmpty()),
            body = this,
        )
        is ChatRenderItem.Single -> item.stableRunKey?.let { key ->
            ChatMessageListRenderRunParams(
                runId = item.stableRunId ?: key.removePrefix("run-"),
                messages = listOf(item.message),
                topPadding = chatDimens.ungroupedMessageSpacing,
                highlighted = false,
                body = this,
            )
        }
    }

@Composable
private fun ChatMessageListRenderRunItem(params: ChatMessageListRenderRunParams) {
    val body = params.body
    val context = body.context
    val highlightModifier = if (params.highlighted) {
        Modifier.background(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            RoundedCornerShape(body.chatShapes.bubbleRadius),
        )
    } else {
        Modifier
    }
    RunBlock(
        messages = params.messages,
        collapsed = params.runId in context.itemState.collapsedRunIds,
        onToggleCollapsed = {
            context.callbacks.onToggleRunCollapsed(params.runId)
        },
        modifier = highlightModifier.padding(top = params.topPadding),
        isStreaming = body.isStreamingRenderItem,
        activeApprovalRequestId = context.itemState.activeApprovalRequestId,
        onApprovalDecision = context.callbacks.onSubmitApproval,
        chatMode = context.chatMode,
        showCompletedDisclosure = body.showTimestamp,
        onOpenToolRunDetails = context.callbacks.onOpenToolRunDetails,
    ) { message, position, rowModifier ->
        RenderChatMessageRow(
            params = RenderChatMessageRowParams(
                message = message,
                position = position,
                context = context,
                isStreamingRenderItem = body.isStreamingRenderItem,
                showTimestamp = body.showTimestamp && message.id == params.messages.last().id,
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
    val showTimestamp: Boolean = false,
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
        rerunEnabled = !context.itemState.isStreaming,
        approvalInFlight = context.itemState.activeApprovalRequestId == message.approvalRequest?.requestId,
        showTimestamp = params.showTimestamp,
        chatMode = context.chatMode,
        highlightedMessageId = context.highlightedMessageId,
        callbacks = context.callbacks,
        reasoningCollapsed = message.id !in context.itemState.expandedReasoningMessageIds,
        onToggleReasoning = { context.callbacks.onToggleReasoningExpanded(message.id) },
        modifier = modifier,
    )
}
