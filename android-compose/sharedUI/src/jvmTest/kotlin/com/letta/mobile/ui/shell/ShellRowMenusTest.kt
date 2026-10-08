package com.letta.mobile.ui.shell

import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellRailEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The row menus every gesture shares (letta-mobile-c3np7.2.12): one definition per row kind. */
class ShellRowMenusTest {

    @Test
    fun aConversationOffersArchiveOrRestoreThenDelete() {
        val events = mutableListOf<String>()
        val active = ShellRowMenus.conversation(archived = false, deleting = false, onArchiveToggle = { events += "archive" }) {
            events += "delete?"
        }
        assertEquals(listOf("Archive chat", "Delete chat"), active.map { it.label })
        active.forEach { it.onClick() }
        assertEquals(listOf("archive", "delete?"), events)

        val archived = ShellRowMenus.conversation(archived = true, deleting = false, onArchiveToggle = {}, onRequestDelete = {})
        assertEquals(listOf("Restore chat", "Delete chat"), archived.map { it.label })
    }

    @Test
    fun aConversationOffersRenameAndPinWhenTheHostDoes() {
        // letta-mobile-bzvro.17
        val events = mutableListOf<String>()
        val manage = ShellConversationManageMenu(pinned = false, onRenameRequest = { events += "rename" }, onPinToggle = { events += "pin" })
        val menu = ShellRowMenus.conversation(archived = false, deleting = false, onArchiveToggle = {}, manage = manage) {}
        assertEquals(listOf("Rename chat", "Pin chat", "Archive chat", "Delete chat"), menu.map { it.label })
        menu.take(2).forEach { it.onClick() }
        assertEquals(listOf("rename", "pin"), events)

        assertEquals("Unpin chat", ShellRowMenus.conversation(false, false, {}, manage.copy(pinned = true)) {}[1].label)
        assertTrue(ShellRowMenus.conversation(archived = false, deleting = true, onArchiveToggle = {}, manage = manage) {}.isEmpty())
    }

    @Test
    fun aConversationBeingDeletedOffersNothing() {
        assertTrue(ShellRowMenus.conversation(archived = false, deleting = true, onArchiveToggle = {}, onRequestDelete = {}).isEmpty())
    }

    @Test
    fun aCanvasOffersArchiveOnlyWhenTheHostKeepsAnArchive() {
        assertTrue(ShellRowMenus.canvas(archived = false, onArchiveToggle = null).isEmpty())
        assertEquals(listOf("Archive canvas"), ShellRowMenus.canvas(archived = false, onArchiveToggle = {}).map { it.label })
        assertEquals(listOf("Restore canvas"), ShellRowMenus.canvas(archived = true, onArchiveToggle = {}).map { it.label })
    }

    @Test
    fun anAgentOffersOpenAndWhateverTheHostSupplies() {
        val entry = ShellRailEntry(key = "Alpha", name = "Alpha", agentId = "a1", orbStyle = 0)
        assertEquals(listOf("Open"), ShellRowMenus.agent(entry, ShellAgentRailActions()).map { it.label })

        val events = mutableListOf<String>()
        val actions = ShellAgentRailActions(
            onAgentSelected = { events += "open:$it" },
            onAgentPinnedChange = { id, pinned -> events += "pin:$id:$pinned" },
            onAgentSettings = { events += "settings:$it" },
        )
        val menu = ShellRowMenus.agent(entry, actions)
        assertEquals(listOf("Open", "Pin agent", "Agent settings"), menu.map { it.label })
        menu.forEach { it.onClick() }
        assertEquals(listOf("open:a1", "pin:a1:true", "settings:a1"), events)

        val pinned = ShellRowMenus.agent(entry.copy(pinned = true), actions)
        assertEquals("Unpin agent", pinned[1].label)
        pinned[1].onClick()
        assertEquals("pin:a1:false", events.last())
    }
}
