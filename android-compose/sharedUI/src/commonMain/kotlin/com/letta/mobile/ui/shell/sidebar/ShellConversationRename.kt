package com.letta.mobile.ui.shell.sidebar

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.components.list.RosterRenameField
import com.letta.mobile.ui.components.list.RosterRenameRequest

/** Test tags of the conversation row's rename and pin. */
object ShellConversationTags {
    const val RENAME_FIELD = "shell_conversation_rename"
    const val PINNED = "shell_conversation_pinned"
    const val SETTLED = "shell_conversation_settled"
    const val MARKER_PICKER = "shell_conversation_marker_picker"
}

/** What the conversation rename field asks for when it is empty. */
internal const val CHAT_NAME_PLACEHOLDER = "Chat name"

/**
 * letta-mobile-bzvro.17: a conversation's title, editable in place: the roster's rename field
 * ([RosterRenameField]) with the chat's placeholder and tag.
 */
@Composable
fun ShellConversationRenameField(
    title: String,
    onRename: (String) -> Unit,
    onDone: () -> Unit,
) {
    RosterRenameField(
        RosterRenameRequest(title = title, onRename = onRename, onDone = onDone, placeholder = CHAT_NAME_PLACEHOLDER),
        modifier = Modifier.testTag(ShellConversationTags.RENAME_FIELD),
    )
}
