package com.letta.mobile.ui.document.workspace

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The document workspace's layout decisions. */
internal object DocumentWorkspaceDimens {
    /** From this width the presentation selector docks beside the document instead of above it. */
    val wideBreakpoint: Dp = 840.dp

    /** The docked presentation selector's column. */
    val selectorWidth: Dp = 280.dp
}
