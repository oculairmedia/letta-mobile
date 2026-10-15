@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.sidebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.host.LettaHost
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
    fun theHostsAgentCardSitsUnderTheNameButNotOnHome() = runComposeUiTest {
        var home by androidx.compose.runtime.mutableStateOf(false)
        setContent {
            MaterialTheme {
                ShellAgentPanel(
                    state = state.copy(home = home),
                    actions = ShellAgentPanelActions(),
                    modifier = Modifier.width(280.dp).fillMaxHeight(),
                    agentCard = { Text("model card") },
                )
            }
        }
        onNodeWithTag(ShellAgentPanelTags.AGENT_CARD).assertExists()
        onNodeWithText("model card").assertExists()
        home = true
        waitForIdle()
        onNodeWithText("model card").assertDoesNotExist()
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

    /** Canvases are archived like conversations: hover the icon to set one aside or bring it back. */
    @Test
    fun hoveringACanvasOffersArchiveOrRestore() = runComposeUiTest {
        val toggled = mutableListOf<Pair<CanvasId, Boolean>>()
        val canvases = state.copy(
            canvases = listOf(
                ShellCanvasRowModel(id = CanvasId("a"), title = "Roadmap", timeLabel = "1h"),
                ShellCanvasRowModel(id = CanvasId("b"), title = "Old board", timeLabel = "2w", archived = true),
            ),
        )
        setContent { Panel(canvases, ShellAgentPanelActions(onArchiveCanvas = { id, archived -> toggled += id to archived })) }
        onNodeWithText("Roadmap").performMouseInput { moveTo(center) }
        onNodeWithContentDescription("Archive canvas").performClick()
        onNodeWithText("Old board").performMouseInput { moveTo(center) }
        onNodeWithContentDescription("Restore canvas").performClick()
        assertEquals(listOf(CanvasId("a") to true, CanvasId("b") to false), toggled)
    }

    /** letta-mobile-ckmub.3.10: the chats and canvases are one roster, walked from the keyboard. */
    @Test
    fun theKeyboardWalksChatsThenCanvasesAndF2RenamesAChat() = runComposeUiTest {
        val events = mutableListOf<String>()
        setContent {
            Panel(
                state,
                ShellAgentPanelActions(
                    onConversationSelected = { events += "open:$it" },
                    onOpenCanvas = { events += "canvas:${it.value}" },
                    onRenameConversation = { id, title -> events += "rename:$id:$title" },
                ),
            )
        }
        val library = onNodeWithTag(ShellAgentPanelTags.LIBRARY)
        library.requestFocus()
        library.performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
            pressKey(Key.DirectionUp)
            pressKey(Key.F2)
        }
        onNodeWithTag(ShellConversationTags.RENAME_FIELD).performTextReplacement("Lisbon")
        onNodeWithTag(ShellConversationTags.RENAME_FIELD).performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(listOf("open:c1", "canvas:k1", "rename:c1:Lisbon"), events) }
    }

    @Test
    fun deletingFromTheRowMenuAsksFirst() = runComposeUiTest {
        val deleted = mutableListOf<String>()
        // A stand-in host: the row menu's entries as plain buttons, the confirmation as one button.
        val host = LettaHost(
            rowMenu = { items, content ->
                Column {
                    content()
                    items.forEach { Text("menu:${it.label}", Modifier.clickable(onClick = it.onClick)) }
                }
            },
            // The confirmation arrives through the host's alert container; draw it in place.
            alert = { spec, content -> Column { Text("alert:${spec.title}"); content() } },
        )
        setContent {
            CompositionLocalProvider(LocalLettaHost provides host) {
                Panel(state, ShellAgentPanelActions(onDeleteConversation = { deleted += it }))
            }
        }
        onNodeWithText("menu:Archive chat").assertExists()
        onNodeWithText("menu:Delete chat").performClick()
        assertEquals(emptyList(), deleted)
        onNodeWithText("alert:Delete chat?").assertExists()
        onNodeWithText("Delete").performClick()
        assertEquals(listOf("c1"), deleted)
        onNodeWithText("alert:Delete chat?").assertDoesNotExist()
    }

    @Test
    fun deleteOnABackendThatOnlyArchivesSaysArchive() = runComposeUiTest {
        val deleted = mutableListOf<String>()
        val host = LettaHost(
            rowMenu = { items, content ->
                Column {
                    content()
                    items.forEach { Text("menu:${it.label}", Modifier.clickable(onClick = it.onClick)) }
                }
            },
            alert = { spec, content -> Column { Text("alert:${spec.title}"); content() } },
        )
        setContent {
            CompositionLocalProvider(LocalLettaHost provides host) {
                Panel(
                    state,
                    ShellAgentPanelActions(onDeleteConversation = { deleted += it }, deleteBehavior = ConversationDeleteBehavior.MovesToArchived),
                )
            }
        }
        onNodeWithText("menu:Delete chat").performClick()
        val copy = ShellDeleteConversationCopy.request("Handoff from local-code", ConversationDeleteBehavior.MovesToArchived)
        assertEquals("Archive chat?", copy.title)
        assertEquals("Archive", copy.confirmLabel)
        assertEquals(false, copy.message.contains("permanently removed"))
        onNodeWithText("alert:${copy.title}").assertExists()
        onNodeWithText(copy.message).assertExists()
        onNodeWithText(copy.confirmLabel).performClick()
        assertEquals(listOf("c1"), deleted)
    }

    @Test
    fun deleteCopyIsPermanentOnlyWhereDeleteIsReal() {
        val real = ShellDeleteConversationCopy.request("Plans", ConversationDeleteBehavior.Permanent)
        assertEquals("Delete chat?", real.title)
        assertEquals("Delete", real.confirmLabel)
        assertEquals(true, real.message.contains("permanently removed"))
    }

    @Test
    fun deleteCopyDoesNotPromiseAnArchiveWhereTheChatLeavesEveryList() {
        val removes = ShellDeleteConversationCopy.request("Plans", ConversationDeleteBehavior.RemovesFromLists)
        assertEquals("Remove chat?", removes.title)
        assertEquals(true, removes.message.contains("won't appear under Archived"))
        assertEquals(true, removes.message.contains("Undo"))
        val archives = ShellDeleteConversationCopy.request("Plans", ConversationDeleteBehavior.MovesToArchived)
        assertEquals(true, archives.message.contains("moved to Archived"))
    }

    /** A stand-in host: the row menu's entries as plain buttons under the row. */
    private val menuAsButtons = LettaHost(
        rowMenu = { items, content ->
            Column {
                content()
                items.forEach { Text("menu:${it.label}", Modifier.clickable(onClick = it.onClick)) }
            }
        },
    )

    @Test
    fun renamingFromTheRowMenuEditsTheTitleInPlace() = runComposeUiTest {
        // letta-mobile-bzvro.17: Enter saves a changed title, trimmed.
        val renames = mutableListOf<Pair<String, String>>()
        setContent {
            CompositionLocalProvider(LocalLettaHost provides menuAsButtons) {
                Panel(state, ShellAgentPanelActions(onRenameConversation = { id, title -> renames += id to title }))
            }
        }

        onNodeWithText("menu:Rename chat").performClick()
        onNodeWithTag(ShellConversationTags.RENAME_FIELD).performTextReplacement("  Lisbon trip ")
        onNodeWithTag(ShellConversationTags.RENAME_FIELD).performKeyInput { pressKey(Key.Enter) }

        runOnIdle { assertEquals(listOf("c1" to "Lisbon trip"), renames) }
        onNodeWithTag(ShellConversationTags.RENAME_FIELD).assertDoesNotExist()
        onNodeWithText("Handoff from local-code").assertExists()
    }

    @Test
    fun escapeOrAnUnchangedTitleRenamesNothing() = runComposeUiTest {
        val renames = mutableListOf<String>()
        setContent { MaterialTheme { ShellConversationRenameField(title = "Trip plans", onRename = { renames += it }, onDone = {}) } }

        onNodeWithTag(ShellConversationTags.RENAME_FIELD).performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag(ShellConversationTags.RENAME_FIELD).performTextReplacement("Other")
        onNodeWithTag(ShellConversationTags.RENAME_FIELD).performKeyInput { pressKey(Key.Escape) }

        runOnIdle { assertEquals(emptyList(), renames) }
    }

    @Test
    fun aPinnedRowShowsItsPinAndItsMenuOffersUnpin() = runComposeUiTest {
        val pins = mutableListOf<Pair<String, Boolean>>()
        val pinned = state.copy(conversations = state.conversations.map { it.copy(pinned = true) })
        setContent {
            CompositionLocalProvider(LocalLettaHost provides menuAsButtons) {
                Panel(pinned, ShellAgentPanelActions(onPinConversation = { id, pin -> pins += id to pin }))
            }
        }

        onNodeWithTag(ShellConversationTags.PINNED, useUnmergedTree = true).assertExists()
        onNodeWithText("menu:Rename chat").assertDoesNotExist()
        onNodeWithText("menu:Unpin chat").performClick()

        assertEquals(listOf("c1" to false), pins)
    }

    @androidx.compose.runtime.Composable
    private fun Panel(state: ShellAgentPanelState, actions: ShellAgentPanelActions) {
        MaterialTheme {
            ShellAgentPanel(state = state, actions = actions, modifier = Modifier.width(280.dp).fillMaxHeight())
        }
    }
}
