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
    val desiredCentroidYPx: Float,
    val originalItemHeightPx: Int,
    val fractionWithinItem: Float,
)

internal data class ChatPinchAnchorCorrection(
    val anchor: ChatPinchAnchor?,
    val deltaPx: Float,
)

internal class ChatPinchAnchorState(
    private val residualTolerancePx: Float = 0.5f,
) {
    var anchor: ChatPinchAnchor? = null
        private set

    fun begin(centroidYPx: Float, visibleItems: List<ChatVisibleItemBounds>): ChatPinchAnchor? {
        if (anchor != null) return anchor
        val item = visibleItems.firstOrNull {
            centroidYPx >= it.topPx && centroidYPx < it.bottomPx
        } ?: return null
        val itemHeightPx = item.heightPx
        if (itemHeightPx <= 0) return null
        return ChatPinchAnchor(
            key = item.key,
            itemIndex = item.index,
            desiredCentroidYPx = centroidYPx,
            originalItemHeightPx = itemHeightPx,
            fractionWithinItem = ((centroidYPx - item.topPx) / itemHeightPx).coerceIn(0f, 1f),
        ).also { anchor = it }
    }

    fun correction(visibleItems: List<ChatVisibleItemBounds>): ChatPinchAnchorCorrection {
        val currentAnchor = anchor ?: return ChatPinchAnchorCorrection(null, 0f)
        val currentItem = visibleItems.firstOrNull { it.key == currentAnchor.key }
        if (currentItem != null) {
            return correctionFor(currentAnchor, currentItem)
        }

        val fallback = visibleItems
            .filter { isStableChatRenderItemKey(it.key) && it.heightPx > 0 }
            .minWithOrNull(compareBy<ChatVisibleItemBounds> { abs(it.index - currentAnchor.itemIndex) }.thenBy { it.index })
            ?: run {
                anchor = null
                return ChatPinchAnchorCorrection(null, 0f)
            }
        val fallbackAnchor = ChatPinchAnchor(
            key = fallback.key,
            itemIndex = fallback.index,
            desiredCentroidYPx = currentAnchor.desiredCentroidYPx,
            originalItemHeightPx = fallback.heightPx,
            fractionWithinItem = currentAnchor.fractionWithinItem,
        )
        anchor = fallbackAnchor
        return correctionFor(fallbackAnchor, fallback)
    }

    fun finish() {
        anchor = null
    }

    private fun correctionFor(anchor: ChatPinchAnchor, item: ChatVisibleItemBounds): ChatPinchAnchorCorrection {
        val contentPointPx = item.topPx + anchor.fractionWithinItem * item.heightPx
        val delta = contentPointPx - anchor.desiredCentroidYPx
        return ChatPinchAnchorCorrection(anchor, if (abs(delta) < residualTolerancePx) 0f else delta)
    }

    private val ChatVisibleItemBounds.heightPx: Int
        get() = bottomPx - topPx
}

internal fun isStableChatRenderItemKey(key: Any): Boolean =
    key !is String || (!key.startsWith("date-") && key != "older-loading")
