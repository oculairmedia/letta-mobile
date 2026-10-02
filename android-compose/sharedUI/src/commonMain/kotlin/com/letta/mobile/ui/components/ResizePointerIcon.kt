package com.letta.mobile.ui.components

import androidx.compose.ui.input.pointer.PointerIcon

/** The cursor shown over a horizontal resize handle; platforms without one return the default. */
expect fun horizontalResizePointerIcon(): PointerIcon

/** Which way a resize handle drags, for its cursor. */
enum class ResizeDirection { Horizontal, Vertical, DiagonalDown, DiagonalUp }

/**
 * The cursor over a resize handle dragging in [direction] (DiagonalDown = top-left/bottom-right,
 * DiagonalUp = top-right/bottom-left); platforms without one return the default.
 */
expect fun resizePointerIcon(direction: ResizeDirection): PointerIcon

/** The cursor over a drag-to-move handle; platforms without one return the default. */
expect fun movePointerIcon(): PointerIcon
