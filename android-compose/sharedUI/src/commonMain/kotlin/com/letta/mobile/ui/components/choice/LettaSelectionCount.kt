package com.letta.mobile.ui.components.choice

import androidx.compose.runtime.Immutable
import androidx.compose.ui.state.ToggleableState

/** How many of [total] things are [chosen]: what a [LettaSelectAllRow] reports as on, off or partly. */
@Immutable
data class LettaSelectionCount(val chosen: Int, val total: Int) {
    /** On when all are chosen, off when none (or there are none), partly otherwise. */
    val state: ToggleableState
        get() = when {
            total == 0 || chosen == 0 -> ToggleableState.Off
            chosen >= total -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        }
}
