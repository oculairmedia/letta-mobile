package com.letta.mobile.ui.components.choice

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One segment of a [LettaSegmentedControl]: its [key], the [label] it shows (and is read by), an
 * optional [icon] before it, and whether it can be picked.
 */
@Immutable
data class LettaSegment(
    val key: String,
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
)
