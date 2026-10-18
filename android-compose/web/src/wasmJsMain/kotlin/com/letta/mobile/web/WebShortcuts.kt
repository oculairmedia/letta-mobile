package com.letta.mobile.web

import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.shell.shortcuts.ShellShortcutId
import com.letta.mobile.ui.shell.shortcuts.ShortcutActions
import com.letta.mobile.ui.shell.shortcuts.ShortcutChrome
import com.letta.mobile.ui.shell.shortcuts.ShortcutLists
import com.letta.mobile.ui.shell.shortcuts.ShortcutStepping
import com.letta.mobile.ui.shell.shortcuts.handleShortcut

/** Web binding of the shared keymap (letta-mobile-k5nbq.4). */
internal fun webShortcutHandler(shell: WebShell): (ShellShortcutId) -> Unit {
    val actions = ShortcutActions(
        overlays = shell.workspace.overlays,
        navigate = shell.workspace::navigate,
        chrome = ShortcutChrome(
            toggleSidebar = { shell.layout.dispatch(ShellLayoutEvent.ToggleSidebar) },
        ),
        lists = ShortcutLists(
            conversations = ShortcutStepping(
                ids = { shell.chat.conversations().map { it.id.value } },
                current = { shell.workspace.selectedConversationId?.value },
                open = { shell.navigator.openConversation(ConversationId(it)) },
            ),
            agents = ShortcutStepping(
                ids = { shell.backend.roster.map { it.first } },
                current = { shell.workspace.focusedAgentId?.value },
                open = { shell.navigator.openAgent(AgentId(it)) },
            ),
        ),
    )
    return { id -> handleShortcut(id, actions) }
}
