package com.letta.mobile.ui.shell.shortcuts

import com.letta.mobile.data.chat.runtime.ConversationNavigation
import com.letta.mobile.data.chat.runtime.ConversationStep
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellOverlays

/** Overlay and navigation the keymap can drive in the shared workspace. */
class ShortcutActions(
    val overlays: ShellOverlays,
    val navigate: (ShellDestination) -> Unit,
    val toggleSidebar: () -> Unit,
    val lists: ShortcutLists,
)

/** Ordered conversation and agent ids the prev/next chords step through. */
class ShortcutLists(
    val conversations: ShortcutStepping = ShortcutStepping(),
    val agents: ShortcutStepping = ShortcutStepping(),
)

/** One ordered list the prev/next chords walk. */
class ShortcutStepping(
    val ids: () -> List<String> = { emptyList() },
    val current: () -> String? = { null },
    val open: (String) -> Unit = {},
)

/**
 * Every entry [handleShortcut] performs. A workspace host binds these minus the overlays it does
 * not draw (the web has no rename dialog).
 */
val WorkspaceShortcuts: Set<ShellShortcutId> = setOf(
    ShellShortcutId.Help,
    ShellShortcutId.Search,
    ShellShortcutId.Find,
    ShellShortcutId.QuickOpen,
    ShellShortcutId.NewConversation,
    ShellShortcutId.Rename,
    ShellShortcutId.Memory,
    ShellShortcutId.FullscreenMemory,
    ShellShortcutId.Schedules,
    ShellShortcutId.Settings,
    ShellShortcutId.PreviousConversation,
    ShellShortcutId.NextConversation,
    ShellShortcutId.PreviousAgent,
    ShellShortcutId.NextAgent,
    ShellShortcutId.ToggleSidebar,
)

/** Runs the catalog intent against the workspace's overlays, navigator, chrome and lists. */
fun handleShortcut(id: ShellShortcutId, actions: ShortcutActions) {
    if (openOverlay(id, actions.overlays)) return
    if (openPage(id, actions.navigate, actions.lists.agents.current())) return
    if (stepList(id, actions.lists)) return
    if (id == ShellShortcutId.ToggleSidebar) actions.toggleSidebar()
}

internal fun openOverlay(id: ShellShortcutId, overlays: ShellOverlays): Boolean {
    when (id) {
        ShellShortcutId.Help -> overlays.shortcutHelp = !overlays.shortcutHelp
        ShellShortcutId.Search, ShellShortcutId.Find, ShellShortcutId.QuickOpen -> overlays.commandPalette = true
        ShellShortcutId.NewConversation -> overlays.newConversation = true
        ShellShortcutId.Rename -> overlays.renameChat = true
        else -> return false
    }
    return true
}

internal fun openPage(id: ShellShortcutId, navigate: (ShellDestination) -> Unit, agent: String?): Boolean {
    val page = shortcutPage(id, agent) ?: return false
    navigate(page)
    return true
}

/** Steps the conversation or agent list for a prev/next entry; false for any other entry. */
fun stepList(id: ShellShortcutId, lists: ShortcutLists): Boolean {
    val stepping = steppingOf(id, lists) ?: return false
    val target = ConversationNavigation.adjacent(stepping.ids(), stepping.current(), directionOf(id))
    if (target != null) stepping.open(target)
    return true
}

/** The page a page entry opens for [agent], or null when [id] is not a page entry. */
fun shortcutPage(id: ShellShortcutId, agent: String?): ShellDestination? = when (id) {
    ShellShortcutId.Memory, ShellShortcutId.FullscreenMemory -> ShellDestination.Memory(agent)
    ShellShortcutId.Schedules -> ShellDestination.Schedules(agent)
    ShellShortcutId.Settings -> ShellDestination.Settings()
    else -> null
}

private fun steppingOf(id: ShellShortcutId, lists: ShortcutLists): ShortcutStepping? = when (id) {
    ShellShortcutId.PreviousConversation, ShellShortcutId.NextConversation -> lists.conversations
    ShellShortcutId.PreviousAgent, ShellShortcutId.NextAgent -> lists.agents
    else -> null
}

private fun directionOf(id: ShellShortcutId): ConversationStep = when (id) {
    ShellShortcutId.PreviousConversation, ShellShortcutId.PreviousAgent -> ConversationStep.Previous
    else -> ConversationStep.Next
}
