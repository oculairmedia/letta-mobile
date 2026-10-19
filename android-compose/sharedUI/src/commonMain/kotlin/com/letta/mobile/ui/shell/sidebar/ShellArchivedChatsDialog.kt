package com.letta.mobile.ui.shell.sidebar

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
import com.letta.mobile.ui.components.list.RosterListTag
import com.letta.mobile.ui.components.list.RosterOptions
import com.letta.mobile.ui.components.list.RosterRowSlots
import com.letta.mobile.ui.components.list.RosterSection
import com.letta.mobile.ui.components.list.RosterSelection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.host.LettaAlertPane
import com.letta.mobile.ui.host.LettaAlertSpec
import com.letta.mobile.ui.host.LettaPaneButton
import com.letta.mobile.ui.host.LettaPaneButtons
import com.letta.mobile.ui.host.LettaPaneKind

/**
 *  (G37): every archived chat in one place, whatever the panel's filter, each
 * with Restore (unarchive on the server) and a tap to open it: a [RosterList] in a dialog pane, so
 * a restored chat leaves and the rows below glide up instead of jumping.
 */
@Composable
fun ShellArchivedChatsDialog(
    rows: List<ShellConversationRowModel>,
    onRestore: (String) -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val spec = LettaAlertSpec(
        title = "Archived chats",
        buttons = LettaPaneButtons(confirm = LettaPaneButton("Close", onDismiss)),
        kind = LettaPaneKind.Dialog,
        testTag = ShellChatOptionsTags.ARCHIVED,
    )
    LettaAlertPane(spec, onDismiss) {
        val sections = remember(rows) { listOf(RosterSection(key = "archived", items = rows)) }
        RosterList(
            RosterSpec(
                sections = sections,
                key = ShellConversationRowModel::id,
                selection = RosterSelection(onSelect = { onOpen(it.id) }),
                options = RosterOptions(testTag = RosterListTag(ARCHIVED_LIST_TAG)),
                empty = { LettaEmptyHint("No archived chats") },
            ),
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
    }
}

private const val ARCHIVED_LIST_TAG = "shell-archived-chats-list"
