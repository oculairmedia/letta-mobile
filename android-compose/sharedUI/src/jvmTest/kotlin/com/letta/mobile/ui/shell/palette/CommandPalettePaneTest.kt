@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.palette

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.search.MessageSearchCapabilities
import com.letta.mobile.data.search.MessageSearchHit
import com.letta.mobile.data.search.MessageSearchQuery
import com.letta.mobile.data.search.MessageSearchSource
import com.letta.mobile.ui.shell.header.UnifiedSearchDestination
import com.letta.mobile.ui.shell.header.UnifiedSearchHit
import com.letta.mobile.ui.shell.header.UnifiedSearchKind
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaPaneTags
import com.letta.mobile.ui.host.ProvideLettaHost
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-ckmub.3.6: the command palette and the quick-query bar compose the shared picker;
 * letta-mobile-ckmub.3.9: over the unified search, the header's own model.
 */
class CommandPalettePaneTest {
    private val items = UnifiedSearchSources(
        conversations = listOf(
            ChatConversationSummary(
                id = "c1",
                title = "Fix the login bug",
                agentName = "Ada",
                updatedAtLabel = "2026-10-01T00:00:00Z",
                lastMessagePreview = "",
                agentId = "a1",
            ),
        ),
        agents = listOf(Agent(id = AgentId("a1"), name = "Ada")),
        destinations = listOf(UnifiedSearchDestination("Settings", "Settings")),
    )

    @Test
    fun thePaletteIsADialogOnPointerAndEnterRunsTheTopMatch() = runComposeUiTest {
        val selected = mutableListOf<UnifiedSearchHit>()
        var dismissed = 0
        setContent {
            ProvideLettaHost(LettaHost.Pointer) {
                MaterialTheme { CommandPalettePane(items, onOpen = { selected += it }, onDismiss = { dismissed++ }) }
            }
        }
        onNodeWithTag(LettaPaneTags.DIALOG).assertExists()
        onNodeWithTag(CommandPaletteTags.SEARCH).performTextInput("settings")
        onNodeWithTag(CommandPaletteTags.ROW_PREFIX + "Conversations-conversations:c1").assertDoesNotExist()
        onNodeWithTag(CommandPaletteTags.SEARCH).performKeyInput { pressKey(Key.Enter) }
        runOnIdle {
            assertEquals(listOf(UnifiedSearchHit(UnifiedSearchKind.Destinations, "Settings")), selected)
            assertEquals(1, dismissed)
        }
    }

    /** The palette finds message text through the host's message search, and a hit opens its conversation. */
    @Test
    fun thePaletteFindsMessageTextAndOpensItsConversation() = runComposeUiTest {
        val selected = mutableListOf<UnifiedSearchHit>()
        val search = object : MessageSearchSource {
            override val capabilities = MessageSearchCapabilities.LOCAL

            override suspend fun search(query: MessageSearchQuery): List<MessageSearchHit> =
                listOf(MessageSearchHit("c1", "a1", "m1", "Ask Emmanuel about the ${query.text} release"))
                    .takeIf { query.text == "emmanuel" }
                    .orEmpty()
        }
        setContent {
            ProvideLettaHost(LettaHost.Pointer) {
                MaterialTheme { CommandPalettePane(items.copy(messageSearch = search), onOpen = { selected += it }, onDismiss = {}) }
            }
        }
        onNodeWithTag(CommandPaletteTags.SEARCH).performTextInput("emmanuel")
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Ask Emmanuel", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(CommandPaletteTags.SEARCH).performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(listOf(UnifiedSearchHit(UnifiedSearchKind.Messages, "c1")), selected) }
    }

    @Test
    fun quickQueryOpensTheHighlightedResultOrAsksTheAgent() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val asked = mutableListOf<Pair<String, String?>>()
        val actions = QuickQueryActions(
            onOpen = { opened += it.id },
            onSubmitPrompt = { text, context -> asked += text to context },
            onClose = {},
        )
        setContent { MaterialTheme { QuickQueryPanel(items, ambientContext = "notes.txt", actions = actions) } }

        // Nothing typed: recent agents head the list.
        onNodeWithText("Recently viewed".uppercase()).assertExists()
        onNodeWithTag(CommandPaletteTags.AMBIENT).assertExists()
        onNodeWithTag(CommandPaletteTags.SEARCH).requestFocus()
        onNodeWithTag(CommandPaletteTags.SEARCH).performTextInput("what is up")
        // No match: Enter asks the current agent with the kept context.
        onNodeWithTag(CommandPaletteTags.SEARCH).performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(listOf<Pair<String, String?>>("what is up" to "notes.txt"), asked) }
    }

    @Test
    fun ctrlEnterAsksEvenWhenAResultMatches() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val asked = mutableListOf<String>()
        val actions = QuickQueryActions(onOpen = { opened += it.id }, onSubmitPrompt = { text, _ -> asked += text }, onClose = {})
        setContent { MaterialTheme { QuickQueryPanel(items, ambientContext = null, actions = actions) } }
        onNodeWithTag(CommandPaletteTags.SEARCH).requestFocus()
        onNodeWithTag(CommandPaletteTags.SEARCH).performTextInput("ada")
        onNodeWithTag(CommandPaletteTags.SEARCH).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        runOnIdle {
            assertEquals(listOf("ada"), asked)
            assertEquals(emptyList<String>(), opened)
        }
    }
}
