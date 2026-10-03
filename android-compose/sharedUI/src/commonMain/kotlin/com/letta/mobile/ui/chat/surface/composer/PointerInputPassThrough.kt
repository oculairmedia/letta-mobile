package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/**
 * Lets pointer input that lands on this layer, or on anything drawn in it, go on to the siblings
 * drawn under it, as if the layer were not there. It takes and consumes nothing itself.
 *
 * Compose ends a hit test at the first sibling (topmost first) whose subtree is hit, unless that
 * sibling's OWN modifiers share input with its siblings; a descendant that shares (a Rive surface
 * in pass-through mode) only shares with ITS siblings. An overlay that merely draws over other
 * controls therefore needs this on its top node.
 */
internal fun Modifier.pointerInputPassThrough(): Modifier = this then PointerInputPassThroughElement

private data object PointerInputPassThroughElement : ModifierNodeElement<PointerInputPassThroughNode>() {
    override fun create(): PointerInputPassThroughNode = PointerInputPassThroughNode()

    override fun update(node: PointerInputPassThroughNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "pointerInputPassThrough"
    }
}

private class PointerInputPassThroughNode : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = Unit

    override fun onCancelPointerInput() = Unit

    override fun sharePointerInputWithSiblings(): Boolean = true
}
