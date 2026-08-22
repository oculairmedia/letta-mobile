package com.letta.mobile.feature.chat.screen.messagelist

import com.letta.mobile.ui.theme.LettaCodeFont
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.letta.mobile.feature.chat.screen.ChatMessageItem
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.LocalChatIsPinching
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.chatShapes

@Composable
internal fun MeasuredChatRenderItem(
    signature: ChatRenderItemGeometrySignature,
    geometryState: ChatMessageGeometryState,
    content: @Composable () -> Unit,
) {
    val isPinching = LocalChatIsPinching.current
    // letta-mobile-geom-cache-wireup: read the cached height for this
    // signature and seed the Box's measured height so Compose skips the
    // initial measure pass when the cache hits. The cache is filled by
    // `onSizeChanged` on the FIRST measure of a row; on subsequent
    // compositions of the same row (scrolling, reducer re-render, the
    // every-frame `foldedViaHolder` cycle of 176 events), the cached
    // height is what we want to use. If the cache misses, Compose
    // measures normally and the first `onSizeChanged` populates the cache.
    //
    // `heightIn(min=…)` instead of `height(…)` so a row whose actual
    // measured height grew (e.g. streaming tail) can still expand — the
    // new larger value overwrites the cache via `onSizeChanged`.
    //
    // First-render gate: the cache is in-memory only and loses everything
    // when the activity is recreated (e.g. navigating to a different
    // conversation and back). On the cold-cache "jump back to current
    // feature" path every row is a miss, and the cache MISS path is the
    // same cost as pre-fix — except the per-row `heightFor` lookup adds
    // a tiny constant. To avoid amplifying the cold-path cost we
    // DON'T apply the heightIn modifier on the row's first render in
    // this composable instance. The row still measures normally and
    // `onSizeChanged` still records the height; subsequent renders
    // (within the same activity) use the cached height. The cost of
    // recording the height on the first render is one Map.put — the
    // savings on subsequent renders are the avoidance of re-measurement.
    val hasMeasuredOnce = remember(signature) { mutableStateOf(false) }
    // Expansion/collapse changes the signature. The old signature may carry
    // a much taller cached minimum; never let that floor survive into the new
    // visual state or a collapsed thought/run cannot shrink.
    LaunchedEffect(signature) {
        hasMeasuredOnce.value = false
    }
    val cachedHeightPx = if (hasMeasuredOnce.value) geometryState.heightFor(signature) else null
    val heightModifier = if (cachedHeightPx != null && cachedHeightPx > 0) {
        val cachedHeightDp = with(LocalDensity.current) { cachedHeightPx.toDp() }
        Modifier.heightIn(min = cachedHeightDp)
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(heightModifier)
            .onSizeChanged { size ->
                if (!isPinching) {
                    geometryState.recordMeasuredHeight(
                        signature = signature,
                        heightPx = size.height,
                    )
                    hasMeasuredOnce.value = true
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
    showTimestamp: Boolean = true,
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
            showTimestamp = showTimestamp,
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
