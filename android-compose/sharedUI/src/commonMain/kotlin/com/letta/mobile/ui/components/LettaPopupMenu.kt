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
                    item.onClick()
                },
            )
        }
    }
}
