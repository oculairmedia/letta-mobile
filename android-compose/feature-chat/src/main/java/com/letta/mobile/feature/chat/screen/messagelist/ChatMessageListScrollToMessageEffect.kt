package com.letta.mobile.feature.chat.screen.messagelist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.letta.mobile.feature.chat.screen.calculateLazyIndexForRenderItem
import kotlinx.coroutines.delay

@Composable
internal fun ChatMessageListScrollToMessageEffect(params: ChatMessageListEffectsParams) {
    LaunchedEffect(params.scrollToMessageId, params.renderItems.size) {
        val target = resolveScrollToMessageTarget(params) ?: return@LaunchedEffect

        params.listState.scrollToItem(
            calculateLazyIndexForRenderItem(
                targetRenderIndex = target.second,
                renderItems = params.renderItems,
            ),
        )
        params.onHighlightedMessageIdChange(target.first)
        params.onHasScrolledToTargetChange(true)
        delay(2000)
        params.onHighlightedMessageIdChange(null)
    }
}

private fun resolveScrollToMessageTarget(
    params: ChatMessageListEffectsParams,
): Pair<String, Int>? {
    val messageId = params.scrollToMessageId ?: return null
    if (params.hasScrolledToTarget) return null
    if (params.renderItems.isEmpty()) return null
    val targetIdx = params.renderItems.indexOfFirst { it.containsMessageId(messageId) }
    if (targetIdx < 0) return null
    return messageId to targetIdx
}
