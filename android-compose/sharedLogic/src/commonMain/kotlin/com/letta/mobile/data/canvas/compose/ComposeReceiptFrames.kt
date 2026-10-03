package com.letta.mobile.data.canvas.compose

/**
 * Keeps a [ComposeReceipt] within [CanvasComposeContract.MAX_RECEIPT_BYTES] once items carry frames
 * (letta-mobile-i9yps.2). Frames come from the placement slot; this only decides which of them fit
 * in the receipt. Children lose their frames first, then top-level items. A dropped frame is never
 * silent: [ComposeReceipt.framesOmitted] and [HINT] say to call canvas_get_layout.
 */
internal object ComposeReceiptFrames {
    const val HINT = "call canvas_get_layout for geometry"

    fun fit(receipt: ComposeReceipt): ComposeReceipt {
        if (within(receipt)) return receipt
        val withoutChildren = dropChildFrames(receipt)
        val childrenMarked = mark(withoutChildren)
        if (lostAFrame(receipt, withoutChildren) && within(childrenMarked)) return childrenMarked
        return mark(dropEveryFrame(withoutChildren))
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

/** `[x, y, w, h]`, half-up, so .5 goes away from zero. */
internal fun Slot.frameInts(): List<Int> = listOf(x, y, width, height).map { value ->
    val shifted = if (value >= 0f) value + 0.5f else value - 0.5f
    shifted.toInt()
}
