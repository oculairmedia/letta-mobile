@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.context

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.context.AgentContextCardInputs
import com.letta.mobile.data.context.AgentContextCardModel
import com.letta.mobile.data.repository.modelcontrol.ModelHandle
import com.letta.mobile.data.repository.modelcontrol.ModelPickerEntry
import com.letta.mobile.data.repository.modelcontrol.ModelPickerGroup
import com.letta.mobile.data.repository.modelcontrol.ModelPickerState
import com.letta.mobile.ui.components.picker.LettaPickerTags
import com.letta.mobile.ui.modelcontrol.ModelPickerActions
import com.letta.mobile.ui.modelcontrol.ModelPickerTags
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-pt1ze.5 on the S8 picker (letta-mobile-ckmub.3.6): the agent settings sheet keeps
 * the model list off its root; the "Model" row opens it as a second level with a pinned search,
 * and a pick returns to the rows.
 */
class AgentSettingsDrillInUiTest {
    private fun entry(value: String, selected: Boolean) =
        ModelPickerEntry(value = value, handle = ModelHandle(value), displayName = value, tier = null, efforts = emptyList(), selected = selected)

    @Test
    fun theModelRowOpensTheListAndAPickReturnsToTheRows() = runComposeUiTest {
        val picked = mutableListOf<String>()
        val state = ModelPickerState(
            groups = listOf(ModelPickerGroup("anthropic", "Anthropic", listOf(entry("opus", true), entry("fable-5", false)))),
        )
        setContent {
            MaterialTheme {
                // A phone-sized pane: the list must get the height, not one row of a nested scroll.
                Box(Modifier.size(360.dp, 640.dp)) {
                    Column {
                        AgentContextSheetContent(
                            model = AgentContextCardModel.present(
                                AgentContextCardInputs("opus", null, null, true, null, compacting = false, turnRunning = false),
                            ),
                            picker = AgentContextPicker(
                                state = state,
                                actions = ModelPickerActions(
                                    onSelect = { picked += it.value },
                                    onQueryChange = {},
                                    onToggleGroup = {},
                                    onRefresh = {},
                                    onEditModels = null,
                                ),
                            ),
                            onCompact = {},
                        )
                    }
                }
            }
        }
        onNodeWithTag(ModelPickerTags.LIST).assertDoesNotExist()
        onNodeWithTag(AgentContextTags.MODEL_ROW).performClick()

        onNodeWithTag(ModelPickerTags.SEARCH).assertExists()
        onNodeWithTag(LettaPickerTags.BACK).assertExists()
        onNodeWithTag(AgentContextTags.COMPACT).assertDoesNotExist()
        onNodeWithTag("${ModelPickerTags.ROW_PREFIX}fable-5").performClick()

        runOnIdle { assertEquals(listOf("fable-5"), picked) }
        onNodeWithTag(ModelPickerTags.LIST).assertDoesNotExist()
        onNodeWithTag(AgentContextTags.MODEL_ROW).assertExists()
    }

    @Test
    fun backLeavesTheListWithoutPicking() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Column {
                    AgentContextSheetContent(
                        model = AgentContextCardModel.present(
                            AgentContextCardInputs("opus", null, null, true, null, compacting = false, turnRunning = false),
                        ),
                        picker = AgentContextPicker(
                            state = ModelPickerState(groups = listOf(ModelPickerGroup("a", "A", listOf(entry("opus", true))))),
                            actions = ModelPickerActions(onSelect = {}, onQueryChange = {}, onToggleGroup = {}, onRefresh = {}, onEditModels = null),
                        ),
                        onCompact = {},
                    )
                }
            }
        }
        onNodeWithTag(AgentContextTags.MODEL_ROW).performClick()
        onNodeWithTag(LettaPickerTags.BACK).performClick()
        onNodeWithTag(AgentContextTags.MODEL_ROW).assertExists()
        onNodeWithText("opus").assertExists()
    }
}
