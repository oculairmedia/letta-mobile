package com.letta.mobile.ui.components.foundation

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * What a [LettaEmptyState] says: the [text] saying why it is empty or what to do, under an optional
 * [title] and an optional [icon].
 */
@Immutable
data class LettaEmptyContent(
    val text: String,
    val title: String? = null,
    val icon: ImageVector? = null,
)
