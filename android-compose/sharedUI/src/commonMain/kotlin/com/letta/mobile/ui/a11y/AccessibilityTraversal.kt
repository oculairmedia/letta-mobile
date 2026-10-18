package com.letta.mobile.ui.a11y

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex

/** Tab order for the shared shell: rail/sidebar, then the timeline, then the composer. */
object AccessibilityTraversal {
    const val SIDEBAR = 0f
    const val TIMELINE = 1f
    const val COMPOSER = 2f
}

fun Modifier.accessibilityTraversal(index: Float): Modifier = semantics {
    isTraversalGroup = true
    traversalIndex = index
}

/** Polite live region for run status and streaming assistant text. */
fun Modifier.politeLiveRegion(): Modifier = semantics {
    liveRegion = LiveRegionMode.Polite
}
