package com.letta.mobile.ui.components.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/**
 * What a [LettaChip] draws around its words: a [leading] mark before them (a live status dot) and a
 * [trailing] mark after them (the shared menu indicator, for a chip that opens a menu or sheet).
 */
@Immutable
class LettaChipSlots(
    val leading: (@Composable () -> Unit)? = null,
    val trailing: (@Composable () -> Unit)? = null,
) {
    companion object {
        /** Words only. */
        val None: LettaChipSlots = LettaChipSlots()
    }
}
