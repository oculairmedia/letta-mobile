@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.context.AgentContextCardInputs
import com.letta.mobile.data.context.AgentContextCardModel
import com.letta.mobile.data.repository.modelcontrol.ModelHandle
import com.letta.mobile.data.repository.modelcontrol.ModelPickerEntry
import com.letta.mobile.data.repository.modelcontrol.ModelPickerGroup
import com.letta.mobile.data.repository.modelcontrol.ModelPickerState
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortStops
import com.letta.mobile.data.repository.modelcontrol.ReasoningTier
import com.letta.mobile.ui.context.AgentContextPicker
import com.letta.mobile.ui.context.AgentContextSheetContent
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-3io8k: one discrete effort slider instead of effort chips under every model. */
class ReasoningEffortSliderUiTest {
    private val stops = ReasoningEffortStops.of(listOf("low", "medium", "high"))!!

    @Test
    fun itNamesTheCurrentStopAndAppliesOnlyWhenSet() = runComposeUiTest {
        val applied = mutableListOf<String?>()
        setContent { MaterialTheme { ReasoningEffortSlider(stops, current = "medium", onApply = { applied += it }) } }
        onNodeWithTag(ReasoningEffortSliderTags.VALUE).assertTextEquals("Med")
        onNodeWithTag(ReasoningEffortSliderTags.SLIDER)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Med"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Effort")))
        // Accessibility "set progress" is a complete gesture: it applies once.
        onNodeWithTag(ReasoningEffortSliderTags.SLIDER).performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        waitForIdle()
        assertEquals(listOf<String?>("high"), applied)
        onNodeWithTag(ReasoningEffortSliderTags.VALUE).assertTextEquals("High")
    }

    @Test
    fun anArrowKeyMovesOneStopAndApplies() = runComposeUiTest {
        val applied = mutableListOf<String?>()
        setContent { MaterialTheme { ReasoningEffortSlider(stops, current = "low", onApply = { applied += it }) } }
        onNodeWithTag(ReasoningEffortSliderTags.SLIDER).requestFocus().performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        assertEquals(listOf<String?>(null), applied)
        onNodeWithTag(ReasoningEffortSliderTags.VALUE).assertTextEquals("Default")
    }

    @Test
    fun theSheetShowsOneSliderForTheSelectedModelOnlyWhenItHasTiers() = runComposeUiTest {
        var picked: Pair<String, String?>? = null
        fun entry(value: String, efforts: List<String>, selected: Boolean) = ModelPickerEntry(
            value = value,
            handle = ModelHandle(value),
            displayName = value,
            tier = if (selected) ReasoningTier.HIGH else null,
            efforts = efforts,
            selected = selected,
            contextWindow = 200_000,
        )
        var tiers by mutableStateOf(listOf("low", "medium", "high"))
        setContent {
            MaterialTheme {
                Column {
                    val state = ModelPickerState(
                        groups = listOf(
                            ModelPickerGroup("anthropic", "Anthropic", listOf(entry("opus", tiers, selected = true), entry("fable-5", emptyList(), false))),
                        ),
                    )
                    AgentContextSheetContent(
                        model = AgentContextCardModel.present(
                            AgentContextCardInputs("opus", "high", null, true, null, compacting = false, turnRunning = false),
                        ),
                        picker = AgentContextPicker(
                            state = state,
                            actions = ModelPickerActions(onSelect = {}, onQueryChange = {}, onToggleGroup = {}, onRefresh = {}, onEditModels = null),
                            onEffortApplied = { e, effort -> picked = e.value to effort },
                        ),
                        onCompact = {},
                    )
                }
            }
        }
        onNodeWithTag(ReasoningEffortSliderTags.VALUE).assertTextEquals("High")
        onNodeWithTag(ReasoningEffortSliderTags.SLIDER).performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        waitForIdle()
        assertEquals("opus" to "low", picked)
        tiers = listOf("high")
        waitForIdle()
        onNodeWithTag(ReasoningEffortSliderTags.SLIDER).assertDoesNotExist()
    }
}

