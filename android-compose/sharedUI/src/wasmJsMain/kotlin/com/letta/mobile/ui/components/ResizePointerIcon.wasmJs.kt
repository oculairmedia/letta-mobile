package com.letta.mobile.ui.components

import androidx.compose.ui.input.pointer.PointerIcon

// Compose for web exposes no public constructor for CSS cursors, so web keeps the default arrow
// over resize and move handles (the documented fallback for platforms without one).

actual fun horizontalResizePointerIcon(): PointerIcon = PointerIcon.Default

actual fun resizePointerIcon(direction: ResizeDirection): PointerIcon = PointerIcon.Default

actual fun movePointerIcon(): PointerIcon = PointerIcon.Default
