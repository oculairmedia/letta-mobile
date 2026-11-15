package com.letta.mobile.ui.components.foundation

import androidx.compose.runtime.Immutable

/**
 * A row's words and state: [title] over an optional one-line [supporting] helper; [enabled] false greys
 * them (and the row's control), [destructive] draws the title in the error colour.
 */
@Immutable
data class LettaRowLabel(
    val title: String,
    val supporting: String? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)
