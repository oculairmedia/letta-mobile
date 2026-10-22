@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.modelcontrol

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.ui.context.ModelPickerHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The one model picker every platform opens, over a host's binding. */
class ModelPickerHostUiTest {
    @Test
    fun aClosedLauncherDrawsNothingAndAnOpenOneThePicker() = runComposeUiTest {
        val picker = testModelPicker(models = listOf(pickerModel("openai/a", "Model A")))
        setContent { MaterialTheme { ModelPickerHost(picker, selectedValue = "openai/a") } }

        onNodeWithTag(ModelPickerTags.PANE).assertDoesNotExist()
        picker.open()
        onNodeWithTag(ModelPickerTags.PANE).assertExists()
        // No Models sheet bound: "Edit Models…" is not offered.
        onNodeWithTag(ModelPickerTags.EDIT).assertDoesNotExist()
    }

    @Test
    fun rePickingTheCurrentModelClosesWithoutASwitch() = runComposeUiTest {
        val picks = RecordedPicks()
        val models = listOf(pickerModel("openai/a", "Model A"), pickerModel("anthropic/b", "Model B"))
        val picker = testModelPicker(models = models, picks = picks)
        setContent { MaterialTheme { ModelPickerHost(picker, selectedValue = "openai/a") } }

        picker.open()
        onNodeWithTag("${ModelPickerTags.ROW_PREFIX}openai/a").performClick()
        runOnIdle {
            assertEquals(emptyList<String>(), picks.models)
            assertFalse(picker.pickerOpen)
        }
    }

    @Test
    fun duplicateCatalogHandlesEachGetTheirOwnRow() = runComposeUiTest {
        // Live catalogs can list one handle under more than one entry; each row keeps its own key.
        val picks = RecordedPicks()
        val models = listOf(
            LlmModel(id = "entry-a", name = "Claude Fable", providerType = "anthropic", handle = "anthropic/claude-fable-5"),
            LlmModel(id = "entry-b", name = "Claude Fable (dup)", providerType = "anthropic", handle = "anthropic/claude-fable-5"),
            LlmModel(id = "entry-c", name = "gpt-4", providerType = "openai", handle = "gpt-4"),
        )
        val picker = testModelPicker(models = models, picks = picks)
        setContent { MaterialTheme { ModelPickerHost(picker, selectedValue = "gpt-4") } }

        picker.open()
        onNodeWithTag("${ModelPickerTags.ROW_PREFIX}entry-a").performClick()
        runOnIdle { assertEquals(listOf("entry-a"), picks.models) }
    }
}
