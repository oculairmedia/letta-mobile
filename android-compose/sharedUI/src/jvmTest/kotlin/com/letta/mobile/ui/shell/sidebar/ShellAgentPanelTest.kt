@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.sidebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.ui.shell.LocalShellChromeDecorations
import com.letta.mobile.ui.shell.ShellChromeDecorations
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared agent panel (letta-mobile-c3np7.2.3): one composable for the desktop sidebar and the phone drawer. */
class ShellAgentPanelTest {

    private val state = ShellAgentPanelState(
        agent = ShellPanelAgent(name = "Meridian", agentId = "agent-1"),
        selectedSection = LensDestination.Schedules,
        conversations = listOf(
            ShellConversationRowModel(id = "c1", title = "Handoff from local-code", preview = "This box", timeLabel = "4m"),
        ),
        canvases = listOf(ShellCanvasRowModel(id = CanvasId("k1"), title = "Canvas (Meridian)", timeLabel = "13h")),
    )

    @Test
    fun drawsTheAgentItsSectionsAndItsLibrary() = runComposeUiTest {
        setContent { Panel(state, ShellAgentPanelActions()) }
        listOf("Meridian", "Memory", "Schedules", "Channels", "Skills", "New chat", "PINNED", "Active", "Archived", "All")
            .forEach { onNodeWithText(it).assertExists() }
        onNodeWithText("Handoff from local-code").assertExists()
        onNodeWithText("This box").assertExists()
        onNodeWithText("4m").assertExists()
        onNodeWithText("CANVASES").assertExists()
        onNodeWithText("Canvas (Meridian)").assertExists()
        onNodeWithText("Settings").assertExists()
        onNodeWithText("Active").assertIsSelected()
    }

    @Test
    fun homeReplacesTheAgentAndItsMenu() = runComposeUiTest {
        setContent { Panel(state.copy(home = true), ShellAgentPanelActions()) }
        onNodeWithText("Home").assertExists()
        onNodeWithTag(ShellAgentPanelTags.AGENT_MENU).assertDoesNotExist()
    }

    @Test
    fun playLensRelabelsTheSections() = runComposeUiTest {
        setContent { Panel(state.copy(mode = WorkPlayMode.Play), ShellAgentPanelActions()) }
        onNodeWithText("Characters").assertExists()
        onNodeWithText("Worlds & Lore").assertExists()
        onNodeWithText("New scene").assertExists()
        onNodeWithText("Channels").assertDoesNotExist()
    }

    @Test
    fun rowsSectionsAndFiltersReportToTheHost() = runComposeUiTest {
        val events = mutableListOf<String>()
        val actions = ShellAgentPanelActions(
            onOpenSection = { events += "section:$it" },
            onOpenSettings = { events += "settings" },
            onNewChat = { events += "new" },
            onEditAgent = { events += "edit" },
            onArchiveFilterChange = { events += "filter:$it" },
            onConversationSelected = { events += "open:$it" },
            onOpenCanvas = { events += "canvas:${it.value}" },
        )
        setContent { Panel(state, actions) }

        onNodeWithText("Memory").performClick()
        onNodeWithText("New chat").performClick()
        onNodeWithText("Archived").performClick()
        onNodeWithText("Handoff from local-code").performClick()
        onNodeWithText("Canvas (Meridian)").performClick()
        onNodeWithText("Settings").performClick()
        onNodeWithText("Meridian").performClick()

        assertEquals(
            listOf("section:Memory", "new", "filter:Archived", "open:c1", "canvas:k1", "settings", "edit"),
            events,
        )
    }

    @Test
    fun hoveringAConversationOffersArchive() = runComposeUiTest {
        val toggled = mutableListOf<Pair<String, Boolean>>()
        setContent { Panel(state, ShellAgentPanelActions(onArchiveConversation = { id, archived -> toggled += id to archived })) }
        onNodeWithText("Handoff from local-code").performMouseInput { moveTo(center) }
        onNodeWithContentDescription("Archive chat").performClick()
        assertEquals(listOf("c1" to true), toggled)
    }

    @Test
    fun deletingFromTheRowMenuAsksFirst() = runComposeUiTest {
        val deleted = mutableListOf<String>()
        // A stand-in host: the row menu's entries as plain buttons, the confirmation as one button.
        val decorations = ShellChromeDecorations(
            rowMenu = { items, content ->
                Column {
                    content()
                    items.forEach { Text("menu:${it.label}", Modifier.clickable(onClick = it.onClick)) }
                }
            },
            confirm = { request, onConfirm, _ -> Text("confirm:${request.confirmLabel}", Modifier.clickable(onClick = onConfirm)) },
        )
        setContent {
            CompositionLocalProvider(LocalShellChromeDecorations provides decorations) {
                Panel(state, ShellAgentPanelActions(onDeleteConversation = { deleted += it }))
            }
        }
        onNodeWithText("menu:Archive chat").assertExists()
        onNodeWithText("menu:Delete chat").performClick()
        assertEquals(emptyList(), deleted)
        onNodeWithText("confirm:Delete").performClick()
        assertEquals(listOf("c1"), deleted)
        onNodeWithText("confirm:Delete").assertDoesNotExist()
    }

    @androidx.compose.runtime.Composable
    private fun Panel(state: ShellAgentPanelState, actions: ShellAgentPanelActions) {
        MaterialTheme {
            ShellAgentPanel(state = state, actions = actions, modifier = Modifier.width(280.dp).fillMaxHeight())
        }
    }
}
