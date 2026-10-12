package com.letta.mobile.ui.shell.sidebar

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.list.RosterList
import com.letta.mobile.ui.components.list.RosterListRow
import com.letta.mobile.ui.components.list.RosterOptions
import com.letta.mobile.ui.components.list.RosterSection
import com.letta.mobile.ui.components.list.RosterRowSlots
import com.letta.mobile.ui.components.list.RosterSelection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.theme.LettaDimens

/**
 * letta-mobile-zxk2n.5 (G37): every archived chat in one place, whatever the panel's filter, each
 * with Restore (unarchive on the server) and a tap to open it: a [RosterList], so a restored chat
 * leaves and the rows below glide up instead of jumping.
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
            val sections = remember(rows) { listOf(RosterSection(key = "archived", items = rows)) }
            RosterList(
                RosterSpec(
                    sections = sections,
                    key = ShellConversationRowModel::id,
                    selection = RosterSelection(onSelect = { onOpen(it.id) }),
                    options = RosterOptions(testTag = ARCHIVED_LIST_TAG),
                    empty = { LettaEmptyHint("No archived chats") },
                ),
                modifier = Modifier.heightIn(max = LettaDimens.Pane.modalMaxHeight),
            ) { chat, row ->
                RosterListRow(
                    spec = LettaListRowSpec(title = listOfNotNull(chat.marker, chat.title).joinToString(" "), trailing = chat.timeLabel),
                    row = row,
                    slots = RosterRowSlots(
                        end = {
                            TextButton(
                                onClick = { onRestore(chat.id) },
                                enabled = !chat.deleting,
                                modifier = Modifier.testTag(ShellChatOptionsTags.restore(chat.id)),
                            ) { Text("Restore") }
                        },
                    ),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private const val ARCHIVED_LIST_TAG = "shell-archived-chats-list"
