package com.letta.mobile.feature.chat.screen.messagelist

import kotlin.math.abs

internal data class ChatVisibleItemBounds(
    val key: Any,
    val index: Int,
    val topPx: Int,
    val bottomPx: Int,
)

internal data class ChatPinchAnchor(
    val key: Any,
    val itemIndex: Int,
    val centroidOffsetPx: Float,
    val desiredContentPointPx: Float,
)

internal data class ChatPinchAnchorCorrection(
    val anchor: ChatPinchAnchor?,
    val deltaPx: Float,
)

internal class ChatPinchAnchorState(
    private val minimumCorrectionPx: Float = 0.5f,
) {
    var anchor: ChatPinchAnchor? = null
        private set

    fun begin(centroidYPx: Float, visibleItems: List<ChatVisibleItemBounds>): ChatPinchAnchor? {
        if (anchor != null) return anchor
        val item = visibleItems.firstOrNull {
            centroidYPx >= it.topPx && centroidYPx < it.bottomPx
        } ?: return null
        return ChatPinchAnchor(
            key = item.key,
            itemIndex = item.index,
            centroidOffsetPx = centroidYPx - item.topPx,
            desiredContentPointPx = centroidYPx,
        ).also { anchor = it }
    }

    fun correction(visibleItems: List<ChatVisibleItemBounds>): ChatPinchAnchorCorrection {
        val currentAnchor = anchor ?: return ChatPinchAnchorCorrection(null, 0f)
        val currentItem = visibleItems.firstOrNull { it.key == currentAnchor.key }
        if (currentItem != null) {
            return correctionFor(currentAnchor, currentItem)
        }

        val fallback = visibleItems
            .filter { isStableChatRenderItemKey(it.key) }
            .minWithOrNull(compareBy<ChatVisibleItemBounds> { abs(it.index - currentAnchor.itemIndex) }.thenBy { it.index })
            ?: run {
                anchor = null
                return ChatPinchAnchorCorrection(null, 0f)
            }
        val fallbackOffset = currentAnchor.centroidOffsetPx.coerceIn(0f, (fallback.bottomPx - fallback.topPx).toFloat())
        val fallbackAnchor = ChatPinchAnchor(
            key = fallback.key,
            itemIndex = fallback.index,
            centroidOffsetPx = fallbackOffset,
            desiredContentPointPx = currentAnchor.desiredContentPointPx,
        )
        anchor = fallbackAnchor
        return correctionFor(fallbackAnchor, fallback)
    }

    fun finish() {
        anchor = null
    }

    private fun correctionFor(anchor: ChatPinchAnchor, item: ChatVisibleItemBounds): ChatPinchAnchorCorrection {
        val delta = item.topPx + anchor.centroidOffsetPx - anchor.desiredContentPointPx
        return ChatPinchAnchorCorrection(anchor, if (abs(delta) < minimumCorrectionPx) 0f else delta)
    }
}

internal fun isStableChatRenderItemKey(key: Any): Boolean =
    key !is String || (!key.startsWith("date-") && key != "older-loading")
