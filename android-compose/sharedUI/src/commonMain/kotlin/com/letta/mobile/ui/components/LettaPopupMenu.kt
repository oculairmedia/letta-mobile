package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.letta.mobile.ui.theme.LettaDimens

/** One row of a [LettaPopupMenu]. */
@Immutable
data class LettaMenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The app's small action menu (the plus buttons, the agent kebab, row overflow): a list of
 * [LettaMenuItem]s on `surfaceContainerHigh`, anchored to whatever it is composed inside.
 *
 * Every menu goes through here so they all look the same and a design change is one edit.
 *
 * A chosen item runs just after the menu closes, on a scope that does not belong to the menu.
 *
 * Both halves are necessary. The wait keeps screen-replacing work (opening the canvas, navigating
 * away) off the frame that tears the popup's own scene layer down. And the scope has to outlive
 * the popup, because callers dismiss by removing it - `if (menuOpen) { LettaPopupMenu(...) }` -
 * so an effect owned by this composable is cancelled before it can run, and every menu item in
 * the app silently does nothing.
 */
@Composable
fun LettaPopupMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<LettaMenuItem>,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        items.forEach { item ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                leadingIcon = item.icon?.let { icon ->
                    {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(LettaDimens.Control.icon),
                        )
                    }
                },
                enabled = item.enabled,
                contentPadding = PaddingValues(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.hair),
                onClick = {
                    onDismiss()
                    runMenuAction(item.onClick)
                },
            )
        }
    }
}

/**
 * Runs a chosen menu item once the menu has gone.
 *
 * Deliberately not a composition-owned scope: the popup is usually removed from the composition by
 * its own dismiss handler, and anything belonging to it dies with it. [MENU_ACTION_DELAY_MS] is
 * about one frame, long enough for the dismissal to be laid out and short enough that no one sees
 * it, and the action still runs on the main dispatcher like any other click.
 */
private fun runMenuAction(action: () -> Unit) {
    menuActionScope.launch {
        delay(MENU_ACTION_DELAY_MS)
        action()
    }
}

/** Outlives every popup on purpose; see [runMenuAction]. */
private val menuActionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** One frame at 60Hz: the dismissal is laid out before the action replaces what is on screen. */
private const val MENU_ACTION_DELAY_MS = 16L
