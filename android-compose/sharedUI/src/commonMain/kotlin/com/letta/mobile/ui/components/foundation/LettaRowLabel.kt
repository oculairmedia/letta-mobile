package com.letta.mobile.ui.components.foundation

import androidx.compose.runtime.Immutable

/**
 * A row's words and state: [title] over an optional [supporting] helper of at most
 * [supportingMaxLines] lines (one by default; a row whose helper explains a consequence lets it
 * wrap); [enabled] false greys them (and the row's control), [destructive] draws the title in the
 * error colour.
 */
@Immutable
data class LettaRowLabel(
    val title: String,
    val supporting: String? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val supportingMaxLines: Int = 1,
)
