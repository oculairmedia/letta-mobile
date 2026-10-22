@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.repository.modelcontrol.ModelPickerState
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-uljk9: a model's thinking and effort options, beside its row (pointer) or in a sheet (touch). */
class ModelPickerEffortUiTest {
    private class Picks {
        val models = mutableListOf<String>()
        val efforts = mutableListOf<Pair<String, String?>>()
    }

    private fun actions(picks: Picks, efforts: Boolean = true) = ModelPickerActions(
        onSelect = { picks.models += it.value },
        onQueryChange = {},
        onToggleGroup = {},
        onRefresh = {},
        onEditModels = null,
        onEffortSelected = if (efforts) ({ entry, effort -> picks.efforts += entry.value to effort }) else null,
    )

    private fun ComposeUiTest.show(
        host: LettaHost,
        picks: Picks,
        efforts: Boolean = true,
        state: ModelPickerState = ModelControlUiFixtures.pickerState(),
    ) {
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                ProvideLettaHost(host) {
                    Box(Modifier.size(900.dp, 760.dp)) {
                        Column { ModelPickerContent(state, actions(picks, efforts), autoFocusSearch = true) }
                    }
                }
            }
        }
    }

    /** Only the OpenAI Codex group, so GPT-5.5 is the first row the keyboard reaches. */
    private fun gptFirst(): ModelPickerState {
        val all = ModelControlUiFixtures.pickerState()
        return all.copy(groups = all.groups.filter { group -> group.entries.any { it.value == GPT } })
    }

    private fun row(handle: String) = "${ModelPickerTags.ROW_PREFIX}$handle"

    @Test
    fun hoveringARowOpensItsFlyoutAndAnEffortThereSelectsModelAndEffort() = runComposeUiTest {
        val picks = Picks()
        show(LettaHost.Pointer, picks)

        onNodeWithTag(row(CODEX)).performMouseInput { enter(center) }
        onNodeWithTag(ModelEffortTags.FLYOUT).assertExists()
        // GPT-5.5 Codex has no "none" level, so no Thinking switch.
        onNodeWithTag(ModelEffortTags.THINKING).assertDoesNotExist()
        onNodeWithTag(ModelEffortTags.LEVEL_PREFIX + "low").performClick()

        runOnIdle {
            assertEquals(listOf<Pair<String, String?>>(CODEX to "low"), picks.efforts)
            assertEquals(emptyList<String>(), picks.models)
        }
        onNodeWithTag(ModelEffortTags.FLYOUT).assertDoesNotExist()
    }

    @Test
    fun theThinkingSwitchShowsWhereAModelCanTurnThinkingOffAndFlippingItPicksNone() = runComposeUiTest {
        val picks = Picks()
        show(LettaHost.Pointer, picks)

        onNodeWithTag(row(GPT)).performMouseInput { enter(center) }
        onNodeWithText("Extra high").assertIsDisplayed()
        onNodeWithTag(ModelEffortTags.LEVEL_PREFIX + "none").assertDoesNotExist()
        onNodeWithTag(ModelEffortTags.THINKING).performClick()

        runOnIdle { assertEquals(listOf<Pair<String, String?>>(GPT to "none"), picks.efforts) }
    }

    @Test
    fun rightOpensTheHighlightedRowsOptionsAndEnterPicksTheHighlightedEffort() = runComposeUiTest {
        val picks = Picks()
        show(LettaHost.Pointer, picks, state = gptFirst())

        onNodeWithTag(ModelPickerTags.SEARCH).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionRight)
        }
        onNodeWithTag(ModelEffortTags.FLYOUT).assertExists()
        // Thinking first, then "Low".
        onNodeWithTag(ModelPickerTags.SEARCH).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }

        runOnIdle {
            assertEquals(listOf<Pair<String, String?>>(GPT to "low"), picks.efforts)
            assertEquals(emptyList<String>(), picks.models)
        }
    }

    @Test
    fun leftClosesTheOptionsAndEnterThenPicksTheRowAtItsDefault() = runComposeUiTest {
        val picks = Picks()
        show(LettaHost.Pointer, picks, state = gptFirst())

        onNodeWithTag(ModelPickerTags.SEARCH).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionLeft)
        }
        onNodeWithTag(ModelEffortTags.FLYOUT).assertDoesNotExist()
        onNodeWithTag(ModelPickerTags.SEARCH).performKeyInput { pressKey(Key.Enter) }

        runOnIdle {
            assertEquals(listOf(GPT), picks.models)
            assertEquals(emptyList<Pair<String, String?>>(), picks.efforts)
        }
    }

    @Test
    fun onTouchTheEffortPillOpensTheOptionsSheet() = runComposeUiTest {
        val picks = Picks()
        show(inlineSheets, picks)

        onNodeWithTag(ModelEffortTags.PILL_PREFIX + CODEX).performClick()
        onNodeWithTag(INLINE_SHEET).assertExists()
        onNodeWithTag(ModelEffortTags.LEVEL_PREFIX + "high").performClick()

        runOnIdle { assertEquals(listOf<Pair<String, String?>>(CODEX to "high"), picks.efforts) }
        onNodeWithTag(INLINE_SHEET).assertDoesNotExist()
    }

    @Test
    fun onTouchALongPressOpensTheSameSheetAndATapSelectsTheRow() = runComposeUiTest {
        val picks = Picks()
        show(inlineSheets, picks)

        onNodeWithTag(row(CODEX)).performTouchInput { longClick() }
        onNodeWithTag(INLINE_SHEET).assertExists()
        onNodeWithTag(ModelEffortTags.FLYOUT).assertDoesNotExist()

        onNodeWithTag(row(MINIMAX)).performClick()
        runOnIdle { assertEquals(listOf(MINIMAX), picks.models) }
    }

    @Test
    fun aHostThatCannotPickEffortsOffersNoOptions() = runComposeUiTest {
        show(inlineSheets, Picks(), efforts = false)

        onNodeWithTag(ModelEffortTags.PILL_PREFIX + CODEX).assertDoesNotExist()
        // The catalog's default still trails the name.
        onNodeWithText("High").assertExists()
    }

    private companion object {
        const val GPT = ModelControlUiFixtures.SELECTED
        const val CODEX = "chatgpt-plus-pro/gpt-5.5-codex"
        const val MINIMAX = "minimax/minimax-m3-lightning"
        const val INLINE_SHEET = "inline_sheet"

        /** Touch, with sheets drawn in place so the test reads them without a window. */
        val inlineSheets = LettaHost.Touch.copy(
            sheet = @Composable { _, content -> Column(Modifier.testTag(INLINE_SHEET)) { content() } },
        )
    }
}
