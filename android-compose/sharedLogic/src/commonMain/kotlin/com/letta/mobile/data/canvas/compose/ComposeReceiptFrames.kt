package com.letta.mobile.data.canvas.compose

import kotlin.math.floor

/**
 * Keeps a [ComposeReceipt] within [CanvasComposeContract.MAX_RECEIPT_BYTES] once items carry frames
 * (letta-mobile-i9yps.2). Frames come from the placement slot; this only decides which of them fit
 * in the receipt. Children lose their frames first, then top-level frames from the end, so an item
 * that kept its frame still has one. A dropped frame is never silent: [ComposeReceipt.framesOmitted]
 * and [HINT] say to call canvas_get_layout.
 */
internal object ComposeReceiptFrames {
    const val HINT = "call canvas_get_layout for geometry"

    fun fit(receipt: ComposeReceipt): ComposeReceipt {
        if (within(receipt)) return receipt
        val withoutChildren = dropChildFrames(receipt)
        val childrenMarked = mark(withoutChildren)
        if (lostAFrame(receipt, withoutChildren) && within(childrenMarked)) return childrenMarked
        return dropTail(withoutChildren)
    }

    /**
     * Drop top-level frames from the end until the marked receipt fits. The last step has no frames
     * and is returned as it is: this runs after the artifact is published, so it must not fail. That
     * a frame-less receipt fits is pinned on the largest receipt by CanvasComposeReceiptSizeTest.
     */
    private fun dropTail(receipt: ComposeReceipt): ComposeReceipt {
        val items = receipt.items.toMutableList()
        for (index in items.indices.reversed()) {
            if (items[index].frame == null) continue
            items[index] = items[index].copy(frame = null)
            val marked = mark(receipt.copy(items = items.toList()))
            if (within(marked)) return marked
        }
        return mark(dropEveryFrame(receipt.copy(items = items.toList())))
    }

    private fun within(receipt: ComposeReceipt): Boolean = bytes(receipt) <= CanvasComposeContract.MAX_RECEIPT_BYTES

    private fun bytes(receipt: ComposeReceipt): Int =
        CanvasComposeContract.json.encodeToString(ComposeReceipt.serializer(), receipt).encodeToByteArray().size

    private fun mark(receipt: ComposeReceipt): ComposeReceipt = receipt.copy(framesOmitted = true, framesHint = HINT)

    private fun dropChildFrames(receipt: ComposeReceipt): ComposeReceipt = receipt.copy(
        items = receipt.items.map { item -> item.copy(children = item.children?.map { it.copy(frame = null) }) },
    )

    private fun dropEveryFrame(receipt: ComposeReceipt): ComposeReceipt = receipt.copy(
        items = receipt.items.map { item ->
            item.copy(frame = null, children = item.children?.map { it.copy(frame = null) })
        },
    )

    private fun lostAFrame(before: ComposeReceipt, after: ComposeReceipt): Boolean = framesOf(before) != framesOf(after)

    private fun framesOf(receipt: ComposeReceipt): List<List<Int>?> =
        receipt.items.flatMap { item -> listOf(item.frame) + item.children.orEmpty().map { it.frame } }
}

/**
 * `[x, y, w, h]` in half-up integers. The edges are rounded, then width and height are the
 * difference, so two slots that touch stay touching. Half-up is [halfUp]: .5 goes toward +infinity.
 */
internal fun Slot.frameInts(): List<Int> {
    val left = halfUp(x)
    val top = halfUp(y)
    val right = halfUp(x + width)
    val bottom = halfUp(y + height)
    return listOf(left, top, right - left, bottom - top)
}

/** Half-up to an integer: 2.5 becomes 3, -2.5 becomes -2. */
internal fun halfUp(value: Float): Int = floor(value + 0.5f).toInt()
