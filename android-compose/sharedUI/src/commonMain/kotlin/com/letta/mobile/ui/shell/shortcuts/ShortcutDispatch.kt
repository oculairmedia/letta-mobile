package com.letta.mobile.ui.shell.shortcuts

import com.letta.mobile.data.chat.runtime.ConversationNavigation
import com.letta.mobile.data.chat.runtime.ConversationStep
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellOverlays

/** Overlay and navigation the keymap can drive. */
class ShortcutActions(
    val overlays: ShellOverlays,
    val navigate: (ShellDestination) -> Unit,
    val chrome: ShortcutChrome,
    val lists: ShortcutLists,
)

/** Sidebar, composer focus, and rename — host chrome the catalog cannot reach alone. */
class ShortcutChrome(
    val toggleSidebar: () -> Unit = {},
    val focusComposer: () -> Unit = {},
    val rename: () -> Unit = {},
    val openFilesystem: () -> Unit = {},
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

/** Runs the catalog intent against the host's overlays, navigator, chrome and lists. */
fun handleShortcut(id: ShellShortcutId, actions: ShortcutActions) {
    if (openOverlay(id, actions.overlays)) return
    if (openPage(id, actions.navigate)) return
    if (stepList(id, actions.lists)) return
    runChrome(id, actions.chrome)
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

internal fun openPage(id: ShellShortcutId, navigate: (ShellDestination) -> Unit): Boolean {
    val page = pageFor(id) ?: return false
    navigate(page)
    return true
}

fun stepList(id: ShellShortcutId, lists: ShortcutLists): Boolean {
    val stepping = steppingOf(id, lists) ?: return false
    val target = ConversationNavigation.adjacent(stepping.ids(), stepping.current(), directionOf(id))
    if (target != null) stepping.open(target)
    return true
}

internal fun runChrome(id: ShellShortcutId, chrome: ShortcutChrome) {
    when (id) {
        ShellShortcutId.ToggleSidebar -> chrome.toggleSidebar()
        ShellShortcutId.FocusComposer -> chrome.focusComposer()
        ShellShortcutId.Filesystem, ShellShortcutId.FileTree -> chrome.openFilesystem()
        else -> Unit
    }
}

private fun pageFor(id: ShellShortcutId): ShellDestination? = when (id) {
    ShellShortcutId.Memory, ShellShortcutId.FullscreenMemory -> ShellDestination.Memory()
    ShellShortcutId.Schedules -> ShellDestination.Schedules()
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
