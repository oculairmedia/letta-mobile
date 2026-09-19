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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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
 * A chosen item runs on the frame AFTER the menu closes. A menu is its own scene layer, with its
 * own root node, and an item that acts immediately does so while that layer is being torn down -
 * so an action that replaces what is on screen (opening the canvas, navigating away) leaves the
 * scene measuring a root node it has already disposed, which throws on the render thread and
 * takes the app down. Waiting one frame costs nothing a person can perceive.
 */
@Composable
fun LettaPopupMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<LettaMenuItem>,
    modifier: Modifier = Modifier,
) {
    var chosen by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(chosen) {
        val action = chosen ?: return@LaunchedEffect
        // The action runs BEFORE the state is cleared. Clearing first re-keys this effect, which
        // cancels it at the very next suspension point - and withFrameNanos is one, so the action
        // was dropped and every menu in the app stopped doing anything at all.
        withFrameNanos { }
        action()
        chosen = null
    }
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
                    chosen = item.onClick
                },
            )
        }
    }
}
