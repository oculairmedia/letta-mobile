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
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.display.ChatDisplayOptions
import com.letta.mobile.data.chat.display.ChatTextSize
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.ui.shell.LocalShellChromeDecorations
import com.letta.mobile.ui.shell.ShellChromeDecorations
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-zxk2n.5: archived chats, chat options, settle and markers in the shared agent panel. */
class ShellConversationOptionsTest {
    private val active = ShellConversationRowModel(id = "c1", title = "Plans", preview = "", timeLabel = "4m")
    private val archived = ShellConversationRowModel(id = "c9", title = "Old notes", preview = "", timeLabel = "3w", archived = true)
    private val state = ShellAgentPanelState(
        agent = ShellPanelAgent(name = "Meridian", agentId = "agent-1"),
        conversations = listOf(active),
        archivedConversations = listOf(archived),
        chatOptions = ShellChatOptionsState(canDownloadTranscript = true),
    )

    @Test
    fun theArchivedChatsDialogRestoresAndOpens() = runComposeUiTest {
        val events = mutableListOf<String>()
        val actions = ShellAgentPanelActions(
            onArchiveConversation = { id, isArchived -> events += "archive:$id:$isArchived" },
            onConversationSelected = { events += "open:$it" },
        )
        setContent { Panel(state, actions) }

        openAgentMenuItem("Archived chats")
        onNodeWithTag(ShellChatOptionsTags.ARCHIVED).assertExists()
        onNodeWithTag(ShellChatOptionsTags.restore("c9")).performClick()
        onNodeWithText("Old notes").performClick()

        assertEquals(listOf("archive:c9:false", "open:c9"), events)
        onNodeWithTag(ShellChatOptionsTags.ARCHIVED).assertDoesNotExist()
    }

    @Test
    fun anEmptyArchiveSaysSo() = runComposeUiTest {
        setContent { Panel(state.copy(archivedConversations = emptyList()), ShellAgentPanelActions()) }
        openAgentMenuItem("Archived chats")
        onNodeWithText("No archived chats").assertExists()
    }

    @Test
    fun chatOptionsSwitchesAndTextSizeReportToTheHost() = runComposeUiTest {
        val display = mutableListOf<ChatDisplayOptions>()
        val sizes = mutableListOf<ChatTextSize>()
        val autoPin = mutableListOf<Boolean>()
        var downloads = 0
        val actions = ShellAgentPanelActions(
            chatOptions = ShellChatOptionsActions(
                onDisplayChange = { display += it },
                onTextSizeChange = { sizes += it },
                onAutoPinNewChatsChange = { autoPin += it },
                onDownloadTranscript = { downloads++ },
            ),
        )
        setContent { Panel(state, actions) }

        openAgentMenuItem("Chat options")
        onNodeWithTag(ShellChatOptionsTags.REASONING).assertIsOn().performClick()
        onNodeWithTag(ShellChatOptionsTags.USAGE).performClick()
        onNodeWithTag(ShellChatOptionsTags.textSize(ChatTextSize.Medium)).assertIsSelected()
        onNodeWithTag(ShellChatOptionsTags.textSize(ChatTextSize.Large)).performClick()
        onNodeWithTag(ShellChatOptionsTags.AUTO_PIN).assertIsOff().performClick()
        onNodeWithTag(ShellChatOptionsTags.DOWNLOAD).performClick()

        assertEquals(listOf(ChatDisplayOptions(showReasoning = false), ChatDisplayOptions(showTokenUsage = false)), display)
        assertEquals(listOf(ChatTextSize.Large), sizes)
        assertEquals(listOf(true), autoPin)
        assertEquals(1, downloads)
    }

    @Test
    fun aHostWithoutAutoPinOrAChatHidesThem() = runComposeUiTest {
        val options = ShellChatOptionsState(canDownloadTranscript = false, offersAutoPin = false)
        setContent {
            MaterialTheme { ShellChatOptionsDialog(options, ShellChatOptionsActions(onDownloadTranscript = {}), onDismiss = {}) }
        }
        onNodeWithTag(ShellChatOptionsTags.AUTO_PIN).assertDoesNotExist()
        onNodeWithTag(ShellChatOptionsTags.DOWNLOAD).assertDoesNotExist()
    }

    @Test
    fun noChatOptionsStateMeansNoMenuEntry() = runComposeUiTest {
        setContent { Panel(state.copy(chatOptions = null), ShellAgentPanelActions()) }
        onNodeWithTag(ShellAgentPanelTags.AGENT_MENU).performClick()
        onNodeWithText("Archived chats").assertExists()
        onNodeWithText("Chat options").assertDoesNotExist()
    }

    @Test
    fun theRowMenuSettlesAndSetsAMarker() = runComposeUiTest {
        val events = mutableListOf<String>()
        val marks = ShellConversationMarkActions(
            onSettle = { id, settled -> events += "settle:$id:$settled" },
            onSetMarker = { id, marker -> events += "marker:$id:$marker" },
            markerChoices = listOf("A", "B"),
        )
        setContent {
            CompositionLocalProvider(LocalShellChromeDecorations provides menuAsButtons) {
                Panel(state.copy(conversations = listOf(active.copy(marker = "A"))), ShellAgentPanelActions(conversationMarks = marks))
            }
        }
        onNodeWithText("A Plans").assertExists()
        onNodeWithText("menu:Settle chat").performClick()
        onNodeWithText("menu:Set marker…").performClick()
        onNodeWithText("B").performClick()

        assertEquals(listOf("settle:c1:true", "marker:c1:B"), events)
    }

    @Test
    fun aSettledRowOffersMoveToActiveAndShowsItsMark() = runComposeUiTest {
        val events = mutableListOf<String>()
        val marks = ShellConversationMarkActions({ id, settled -> events += "$id:$settled" }, { _, _ -> }, emptyList())
        setContent {
            CompositionLocalProvider(LocalShellChromeDecorations provides menuAsButtons) {
                Panel(state.copy(conversations = listOf(active.copy(settled = true))), ShellAgentPanelActions(conversationMarks = marks))
            }
        }
        onNodeWithTag(ShellConversationTags.SETTLED, useUnmergedTree = true).assertExists()
        onNodeWithText("menu:Move to active").performClick()
        assertEquals(listOf("c1:false"), events)
    }

    @Test
    fun settledRowsSinkAndMarkersLeadTheTitle() {
        val summaries = listOf("a", "b", "c").map { ChatConversationSummary(id = it, title = it, agentName = "", updatedAtLabel = "", lastMessagePreview = "") }
        val rows = ShellSidebarMapping.conversationRows(
            summaries,
            ShellConversationMarks(pinnedIds = setOf("c"), settledIds = setOf("a"), markers = mapOf("b" to "X")),
        ) { it }
        assertEquals(listOf("c", "b", "a"), rows.map { it.id })
        assertEquals(listOf(false, false, true), rows.map { it.settled })
        assertEquals("X", rows[1].marker)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.openAgentMenuItem(label: String) {
        onNodeWithTag(ShellAgentPanelTags.AGENT_MENU).performClick()
        onNodeWithText(label).performClick()
    }

    /** A stand-in host: the row menu's entries as plain buttons under the row. */
    private val menuAsButtons = ShellChromeDecorations(
        rowMenu = { items, content ->
            Column {
                content()
                items.forEach { Text("menu:${it.label}", Modifier.clickable(onClick = it.onClick)) }
            }
        },
    )

    @androidx.compose.runtime.Composable
    private fun Panel(state: ShellAgentPanelState, actions: ShellAgentPanelActions) {
        MaterialTheme {
            ShellAgentPanel(state = state, actions = actions, modifier = Modifier.width(280.dp).fillMaxHeight())
        }
    }
}
