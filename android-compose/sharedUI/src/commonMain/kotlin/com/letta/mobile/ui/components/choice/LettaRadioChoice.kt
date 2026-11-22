package com.letta.mobile.ui.components.choice

import androidx.compose.runtime.Immutable

/**
 * What a [LettaRadioList] shows: its [options] and which one is [selected] (by key, null for none).
 * [enabled] false disables every option. [error] is a problem with the choice as a whole, shown
 * under the list and announced.
 */
@Immutable
data class LettaRadioChoice(
    val options: List<LettaChoiceOption>,
    val selected: String?,
    val enabled: Boolean = true,
    val error: String? = null,
)
