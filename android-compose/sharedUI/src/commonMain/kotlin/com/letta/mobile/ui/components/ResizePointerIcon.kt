package com.letta.mobile.ui.components

import androidx.compose.ui.input.pointer.PointerIcon

/** The cursor shown over a horizontal resize handle; platforms without one return the default. */
expect fun horizontalResizePointerIcon(): PointerIcon
