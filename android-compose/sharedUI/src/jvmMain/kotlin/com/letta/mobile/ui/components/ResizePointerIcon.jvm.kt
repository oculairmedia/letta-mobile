package com.letta.mobile.ui.components

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

actual fun horizontalResizePointerIcon(): PointerIcon = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

actual fun resizePointerIcon(direction: ResizeDirection): PointerIcon = PointerIcon(
    Cursor(
        when (direction) {
            ResizeDirection.Horizontal -> Cursor.E_RESIZE_CURSOR
            ResizeDirection.Vertical -> Cursor.N_RESIZE_CURSOR
            ResizeDirection.DiagonalDown -> Cursor.SE_RESIZE_CURSOR
            ResizeDirection.DiagonalUp -> Cursor.NE_RESIZE_CURSOR
        },
    ),
)

actual fun movePointerIcon(): PointerIcon = PointerIcon(Cursor(Cursor.MOVE_CURSOR))
