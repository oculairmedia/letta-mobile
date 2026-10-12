@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.palette

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.letta.mobile.data.search.PaletteItem
import com.letta.mobile.data.search.PaletteItemKind
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaPaneTags
import com.letta.mobile.ui.host.ProvideLettaHost
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-ckmub.3.6: the command palette and the quick-query bar compose the shared picker. */
class CommandPalettePaneTest {
    private val items = listOf(
        PaletteItem("c1", "Fix the login bug", "Ada", PaletteItemKind.Conversation),
        PaletteItem("a1", "Ada", null, PaletteItemKind.Agent, orbIndex = 0),
        PaletteItem("Settings", "Settings", null, PaletteItemKind.Destination),
    )

    @Test
    fun thePaletteIsADialogOnPointerAndEnterRunsTheTopMatch() = runComposeUiTest {
        val selected = mutableListOf<String>()
        var dismissed = 0
        setContent {
            ProvideLettaHost(LettaHost.Pointer) {
                MaterialTheme { CommandPalettePane(items, onSelect = { selected += it.id }, onDismiss = { dismissed++ }) }
            }
        }
        onNodeWithTag(LettaPaneTags.DIALOG).assertExists()
        onNodeWithTag(CommandPaletteTags.SEARCH).performTextInput("settings")
        onNodeWithTag(CommandPaletteTags.ROW_PREFIX + "Conversation-c1").assertDoesNotExist()
        onNodeWithTag(CommandPaletteTags.SEARCH).performKeyInput { pressKey(Key.Enter) }
        runOnIdle {
            assertEquals(listOf("Settings"), selected)
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun quickQueryOpensTheHighlightedResultOrAsksTheAgent() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val asked = mutableListOf<Pair<String, String?>>()
        val actions = QuickQueryActions(
            onSelectItem = { opened += it.id },
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
        val actions = QuickQueryActions(onSelectItem = { opened += it.id }, onSubmitPrompt = { text, _ -> asked += text }, onClose = {})
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
