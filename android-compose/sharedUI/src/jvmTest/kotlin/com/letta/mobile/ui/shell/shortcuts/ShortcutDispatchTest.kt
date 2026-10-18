package com.letta.mobile.ui.shell.shortcuts

import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellOverlays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-k5nbq.4: overlay, navigation and prev/next stepping. */
class ShortcutDispatchTest {
    @Test
    fun searchAndHelpToggleTheOverlayStack() {
        val overlays = ShellOverlays()
        val actions = actions(overlays)
        handleShortcut(ShellShortcutId.Search, actions)
        assertTrue(overlays.commandPalette)
        handleShortcut(ShellShortcutId.Help, actions)
        assertTrue(overlays.shortcutHelp)
        handleShortcut(ShellShortcutId.Help, actions)
        assertFalse(overlays.shortcutHelp)
        handleShortcut(ShellShortcutId.NewConversation, actions)
        assertTrue(overlays.newConversation)
    }

    @Test
    fun memorySchedulesAndSettingsNavigate() {
        val opened = mutableListOf<ShellDestination>()
        val actions = actions(navigate = { opened += it })
        handleShortcut(ShellShortcutId.Memory, actions)
        handleShortcut(ShellShortcutId.Schedules, actions)
        handleShortcut(ShellShortcutId.Settings, actions)
        assertEquals(
            listOf(ShellDestination.Memory(), ShellDestination.Schedules(), ShellDestination.Settings()),
            opened,
        )
    }

    @Test
    fun nextAndPreviousConversationWalkTheOrderedList() {
        val opened = mutableListOf<String>()
        val current = mutableListOf("b")
        val actions = actions(
            lists = ShortcutLists(
                conversations = ShortcutStepping(
                    ids = { listOf("a", "b", "c") },
                    current = { current.last() },
                    open = {
                        opened += it
                        current += it
                    },
                ),
            ),
        )
        handleShortcut(ShellShortcutId.NextConversation, actions)
        handleShortcut(ShellShortcutId.PreviousConversation, actions)
        assertEquals(listOf("c", "b"), opened)
    }

    @Test
    fun nextAgentWalksTheRoster() {
        val opened = mutableListOf<String>()
        val actions = actions(
            lists = ShortcutLists(
                agents = ShortcutStepping(
                    ids = { listOf("ada", "bo") },
                    current = { "ada" },
                    open = { opened += it },
                ),
            ),
        )
        handleShortcut(ShellShortcutId.NextAgent, actions)
        assertEquals(listOf("bo"), opened)
    }

    @Test
    fun sidebarChordTogglesChrome() {
        var toggled = 0
        val actions = actions(chrome = ShortcutChrome(toggleSidebar = { toggled += 1 }))
        handleShortcut(ShellShortcutId.ToggleSidebar, actions)
        assertEquals(1, toggled)
    }
}

private fun actions(
    overlays: ShellOverlays = ShellOverlays(),
    navigate: (ShellDestination) -> Unit = {},
    chrome: ShortcutChrome = ShortcutChrome(),
    lists: ShortcutLists = ShortcutLists(),
) = ShortcutActions(overlays, navigate, chrome, lists)
