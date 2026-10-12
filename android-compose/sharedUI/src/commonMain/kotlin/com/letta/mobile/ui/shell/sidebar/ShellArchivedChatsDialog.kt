package com.letta.mobile.ui.shell.sidebar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.theme.LettaDimens

/**
 * letta-mobile-zxk2n.5 (G37): every archived chat in one place, whatever the panel's filter, each
 * with Restore (unarchive on the server) and a tap to open it. A restored chat leaves the list as
 * soon as the host's roster says it is active again.
 */
@Composable
fun ShellArchivedChatsDialog(
    rows: List<ShellConversationRowModel>,
    onRestore: (String) -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ShellChatOptionsTags.ARCHIVED),
        title = { Text("Archived chats") },
        text = {
            if (rows.isEmpty()) {
                LettaEmptyHint("No archived chats")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = LettaDimens.Pane.modalMaxHeight)) {
                    items(items = rows, key = { it.id }) { row ->
                        // A restored chat leaves; the rows below glide up instead of jumping.
                        Box(shellRowMotion()) { ArchivedChatRow(row, onRestore, onOpen) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ArchivedChatRow(row: ShellConversationRowModel, onRestore: (String) -> Unit, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        TextButton(onClick = { onOpen(row.id) }, modifier = Modifier.weight(1f)) {
            Text(
                text = listOfNotNull(row.marker, row.title).joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(row.timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(
            onClick = { onRestore(row.id) },
            enabled = !row.deleting,
            modifier = Modifier.testTag(ShellChatOptionsTags.restore(row.id)),
        ) { Text("Restore") }
    }
}
