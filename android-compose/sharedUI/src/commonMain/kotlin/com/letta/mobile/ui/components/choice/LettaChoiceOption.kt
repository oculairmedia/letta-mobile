package com.letta.mobile.ui.components.choice

import androidx.compose.runtime.Immutable

/**
 * One option of a [LettaRadioList]: its [key], the [label] it shows, an optional [description] under
 * the label (what picking it means), whether it can be picked, and a test [tag] for its row.
 */
@Immutable
data class LettaChoiceOption(
    val key: String,
    val label: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val tag: String? = null,
)
