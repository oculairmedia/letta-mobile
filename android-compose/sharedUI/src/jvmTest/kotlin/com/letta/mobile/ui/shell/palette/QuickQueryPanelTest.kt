@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.palette

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.search.PaletteItem
import com.letta.mobile.data.search.PaletteItemKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-ckmub.3.14: the quick-query bar is the whole drawn surface, no window needed — the
 * desktop keeps only its always-on-top window, the title capture and the sanitizing.
 */
class QuickQueryPanelTest {
    private val items = listOf(PaletteItem("a1", "Ada", null, PaletteItemKind.Agent, orbIndex = 0))

    @Test
    fun droppingTheAmbientChipAsksWithoutContextAndCloses() = runComposeUiTest {
        val asked = mutableListOf<Pair<String, String?>>()
        var closed = 0
        val actions = QuickQueryActions(onSelectItem = {}, onSubmitPrompt = { text, context -> asked += text to context }, onClose = { closed++ })
        setContent { MaterialTheme { QuickQueryPanel(items, ambientContext = "notes.txt", actions = actions) } }
        onNodeWithTag(CommandPaletteTags.QUICK_QUERY).assertExists()
        onNodeWithContentDescription("Remove context").performClick()
        onNodeWithTag(CommandPaletteTags.AMBIENT).assertDoesNotExist()
        onNodeWithTag(CommandPaletteTags.SEARCH).requestFocus()
        onNodeWithTag(CommandPaletteTags.SEARCH).performTextInput("zzz no match")
        onNodeWithTag(CommandPaletteTags.SEARCH).performKeyInput { pressKey(Key.Enter) }
        runOnIdle {
            assertEquals(listOf<Pair<String, String?>>("zzz no match" to null), asked)
            assertEquals(1, closed)
        }
    }

    @Test
    fun noAmbientContextShowsNoChip() = runComposeUiTest {
        val actions = QuickQueryActions(onSelectItem = {}, onSubmitPrompt = { _, _ -> }, onClose = {})
        setContent { MaterialTheme { QuickQueryPanel(items, ambientContext = null, actions = actions) } }
        onNodeWithTag(CommandPaletteTags.AMBIENT).assertDoesNotExist()
    }
}
