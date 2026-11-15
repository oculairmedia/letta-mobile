package com.letta.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.components.foundation.LettaMenuEntry
import com.letta.mobile.ui.components.foundation.LettaMenuItem
import com.letta.mobile.ui.host.LettaMenu

/**
 * The app's small action menu (the plus buttons, the agent kebab, row overflow): a list of
 * [LettaMenuItem]s in the host's menu container ([LettaMenu]), anchored to whatever it is composed
 * inside. Every row is the shared menu row, so every menu looks the same and a design change is one
 * edit; a menu that needs dividers or a status line among its rows composes those rows inside
 * [LettaMenu] itself, with the same row (LettaMenuEntry).
 *
 * A chosen item runs just after the menu closes, on a scope that does not belong to the menu (see
 * [com.letta.mobile.ui.components.foundation.LocalMenuActionScope]).
 */
@Composable
fun LettaPopupMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<LettaMenuItem>,
    modifier: Modifier = Modifier,
) {
    LettaMenu(expanded = expanded, onDismiss = onDismiss, modifier = modifier) {
        items.forEach { item -> LettaMenuEntry(item, onDismiss) }
    }
}

