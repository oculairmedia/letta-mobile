@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.choice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.settings.SettingsOption
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.ui.components.foundation.LettaRowLabel
import com.letta.mobile.ui.settings.radioList
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The radio list shows every option with its description and picks with the whole row; a group error
 * is shown; select-all is partly checked while some are chosen, chooses all, then clears; and a
 * settings choice whose every option explains itself reads as a radio list. Renders the controls in
 * both themes at the phone, tablet and desktop widths to build/choice-list-snapshots.
 */
class LettaChoiceListsUiTest {
    private val modes = listOf(
        LettaChoiceOption("ask", "Ask first", "The agent asks before every tool call.", tag = "mode-ask"),
        LettaChoiceOption("edits", "Accept edits", "File edits go ahead; anything else asks.", tag = "mode-edits"),
        LettaChoiceOption("plan", "Plan only", "The agent plans but changes nothing.", enabled = false, tag = "mode-plan"),
    )

    @Test
    fun theWholeRowPicksAndEveryDescriptionShows() = runComposeUiTest {
        var selected by mutableStateOf("ask")
        setContent { MaterialTheme { LettaRadioList(LettaRadioChoice(modes, selected, error = "Pick one to continue."), onSelect = { selected = it }) } }

        onNodeWithText("File edits go ahead; anything else asks.").assertExists()
        onNodeWithText("The agent plans but changes nothing.").assertExists()
        onNodeWithTag("mode-edits").performClick()
        waitForIdle()
        assertEquals("edits", selected)
        onNodeWithTag("mode-edits").assertIsSelected()
        onNodeWithTag("mode-plan").assertIsNotEnabled()
        onNodeWithText("Pick one to continue.").assertExists()
    }

    private fun toggleState(state: ToggleableState) = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

    @Test
    fun selectAllIsPartlyCheckedThenChoosesAllThenClears() = runComposeUiTest {
        var chosen by mutableStateOf(1)
        val calls = mutableListOf<Boolean>()
        setContent {
            MaterialTheme {
                LettaSelectAllRow(LettaRowLabel("Select all"), LettaSelectionCount(chosen, total = 3), onSelectAll = { all ->
                    calls += all
                    chosen = if (all) 3 else 0
                }, modifier = Modifier.testTag(ALL))
            }
        }
        onNodeWithTag(ALL).assert(toggleState(ToggleableState.Indeterminate)).performClick()
        waitForIdle()
        onNodeWithTag(ALL).assert(toggleState(ToggleableState.On)).performClick()
        waitForIdle()
        onNodeWithTag(ALL).assert(toggleState(ToggleableState.Off))
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun aSettingsChoiceWhoseOptionsExplainThemselvesIsARadioList() {
        val explained = SettingsRow.Choice(
            key = "k",
            title = "Mode",
            options = listOf(SettingsOption("a", "A", "What A does"), SettingsOption("b", "B", "What B does")),
            selected = "a",
            onSelect = {},
        )
        assertTrue(explained.radioList())
        assertFalse(explained.copy(options = listOf(SettingsOption("a", "A"), SettingsOption("b", "B", "What B does"))).radioList())
        assertFalse(explained.copy(alwaysList = true).radioList())
    }

    private fun snapshot(width: Dp, dark: Boolean) = runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
        setContent {
            SharedMaterialTheme(darkTheme = dark) {
                Column(
                    Modifier.width(width).background(MaterialTheme.colorScheme.background).padding(16.dp).testTag(FRAME),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LettaRadioList(LettaRadioChoice(modes, selected = "edits"), onSelect = {})
                    LettaSelectAllRow(LettaRowLabel("Select all"), LettaSelectionCount(chosen = 1, total = 3), onSelectAll = {})
                    LettaCheckboxRow(LettaRowLabel("Research agent"), ToggleableState.On, onClick = {})
                    LettaCheckboxRow(LettaRowLabel("Writer", "Drafts and edits documents."), ToggleableState.Off, onClick = {})
                }
            }
        }
        waitForIdle()
        val out = File("build/choice-list-snapshots").apply { mkdirs() }
            .resolve("choices-${if (dark) "dark" else "light"}-${width.value.toInt()}dp.png")
        ImageIO.write(onNodeWithTag(FRAME).captureToImage().toAwtImage(), "png", out)
        assertTrue(out.length() > 0)
    }

    @Test
    fun choicesAtEveryWidth() = WIDTHS.forEach { width -> listOf(false, true).forEach { snapshot(width, it) } }

    private companion object {
        const val ALL = "select-all"
        const val FRAME = "choice-frame"
        const val WINDOW_WIDTH = 1300
        const val WINDOW_HEIGHT = 700
        val WIDTHS = listOf(360.dp, 700.dp, 760.dp, 1200.dp)
    }
}
