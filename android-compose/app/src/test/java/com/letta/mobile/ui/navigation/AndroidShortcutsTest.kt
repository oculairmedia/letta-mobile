package com.letta.mobile.ui.navigation

import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.shell.shortcuts.ShellShortcutId
import com.letta.mobile.ui.shell.shortcuts.ShortcutLists
import com.letta.mobile.ui.shell.shortcuts.ShortcutStepping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** letta-mobile-k5nbq.11: Android shortcut set and dispatch behavior. */
class AndroidShortcutsTest {
    @Test
    fun newConversationOpensFreshChatWithCurrentChatAgentInsteadOfFirstAgent() {
        val freshChats = mutableListOf<String>()
        val navigated = mutableListOf<ShellDestination>()
        val lists = ShortcutLists(
            agents = ShortcutStepping(ids = { listOf("agent-first", "agent-current") }, current = { "agent-current" }),
        )

        handleAndroidShortcut(
            id = ShellShortcutId.NewConversation,
            lists = lists,
            onHelp = {},
            navigator = AndroidShortcutNavigator(
                currentChatAgentId = "agent-current",
                onFreshChat = { freshChats += it },
                onNavigate = { navigated += it },
            ),
        )

        assertEquals(listOf("agent-current"), freshChats)
        assertTrue(navigated.isEmpty())
    }

    @Test
    fun newConversationOutsideAChatOpensTheConversationList() {
        val freshChats = mutableListOf<String>()
        val navigated = mutableListOf<ShellDestination>()
        val lists = ShortcutLists(
            agents = ShortcutStepping(ids = { listOf("agent-first") }, current = { "agent-first" }),
        )

        handleAndroidShortcut(
            id = ShellShortcutId.NewConversation,
            lists = lists,
            onHelp = {},
            navigator = AndroidShortcutNavigator(
                currentChatAgentId = null,
                onFreshChat = { freshChats += it },
                onNavigate = { navigated += it },
            ),
        )

        assertTrue(freshChats.isEmpty())
        assertEquals(listOf(ShellDestination.Chat()), navigated)
    }

    @Test
    fun unboundPaletteAndSidebarChordsAreExcludedAndDoNothing() {
        val excluded = listOf(
            ShellShortcutId.Search,
            ShellShortcutId.Find,
            ShellShortcutId.QuickOpen,
            ShellShortcutId.Rename,
            ShellShortcutId.ToggleSidebar,
        )
        excluded.forEach { id ->
            assertFalse("$id should not be bound on Android", id in AndroidShortcuts)
            var helpOpened = false
            val navigated = mutableListOf<ShellDestination>()
            handleAndroidShortcut(
                id = id,
                lists = ShortcutLists(),
                onHelp = { helpOpened = true },
                navigator = AndroidShortcutNavigator(onNavigate = { navigated += it }),
            )
            assertFalse(helpOpened)
            assertTrue(navigated.isEmpty())
        }
    }

    @Test
    fun everyBoundAndroidShortcutHasAnEffect() {
        AndroidShortcuts.forEach { id ->
            val effects = mutableListOf<String>()
            val stepping = ShortcutStepping(
                ids = { listOf("a", "b", "c") },
                current = { "b" },
                open = { effects += "step:$it" },
            )
            handleAndroidShortcut(
                id = id,
                lists = ShortcutLists(conversations = stepping, agents = stepping),
                onHelp = { effects += "help" },
                navigator = AndroidShortcutNavigator(
                    currentChatAgentId = "b",
                    onFreshChat = { effects += "fresh:$it" },
                    onNavigate = { effects += "nav:$it" },
                ),
            )
            assertTrue("$id had no effect on Android", effects.isNotEmpty())
        }
    }
}
